package com.midnightbrewer.reference.picc;

import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.ActivationException;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.pcd.PcdCommand;
import com.midnightbrewer.reference.pcd.PcdResponse;
import com.midnightbrewer.reference.pcd.Rc522Driver;
import com.midnightbrewer.reference.pcd.crc.CrcCalculator;
import com.midnightbrewer.reference.util.Hex;
import com.midnightbrewer.reference.util.Timebase;

import java.util.Objects;
import java.util.Optional;

/**
 * ISO 14443-3 activation: find a card, resolve its UID through the cascade, and
 * select it.
 *
 * <p>A port of {@code MFRC522_Request}, {@code MFRC522_AnticollCascade},
 * {@code MFRC522_SelectTagCascade} and {@code MFRC522_Halt}, sequenced the way
 * {@code platform_activate_card} sequences them in {@code desfiire.c}.
 *
 * <p>The class stops where ISO 14443-3 stops. RATS and everything after it are
 * part 4 and live in the ISO-DEP layer, which composes this one. The split
 * matters: a MIFARE Classic reader would use this class and never touch part 4,
 * and both layers get to be tested on their own.
 */
public final class PiccActivator {

    /** ATQA length in bits. The C requires exactly this from a REQA. */
    private static final int ATQA_BIT_LENGTH = 0x10;

    /** SAK + CRC length in bits. The C requires exactly this from a SELECT. */
    private static final int SAK_BIT_LENGTH = 0x18;

    /** Four UID bytes plus BCC. */
    private static final int CASCADE_REPLY_LENGTH = 5;

    /**
     * Settle time between activation steps, from {@code platform_sleep_ms(app, 1)}
     * after every anticollision and every SELECT. A DESFire needs a moment
     * between state transitions; without it the next frame can arrive while the
     * card is still moving and gets ignored.
     */
    private static final long STEP_SETTLE_MS = 1L;

    /**
     * Extra settle after the cascade completes, before part 4 activation. The C
     * does {@code platform_sleep_ms(app, 2)} immediately before calling RATS.
     */
    private static final long PRE_RATS_SETTLE_MS = 2L;

    private final Rc522Driver driver;
    private final CrcCalculator crc;
    private final Timebase timebase;
    private final ProtocolTrace trace;

    public PiccActivator(Rc522Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.crc = driver.crc();
        this.timebase = driver.timebase();
        this.trace = driver.trace();
    }

    /**
     * {@code platform_card_present}: try REQA, and if that finds nothing, try
     * WUPA.
     *
     * <p>Both are needed. REQA only reaches cards in IDLE, so a card that was
     * HALTed by a previous transaction -- which is every card this reader has
     * already served -- is invisible to it and answers only WUPA.
     */
    public boolean isCardPresent() throws NfcException {
        if (request(PiccCommand.REQUEST_IDLE).isPresent()) {
            return true;
        }
        return request(PiccCommand.WAKE_UP).isPresent();
    }

    /**
     * {@code MFRC522_Request}: send REQA or WUPA and read the ATQA.
     *
     * <p>Returns empty rather than throwing, because "no card" is the expected
     * answer almost all of the time and a polling loop should not be built out
     * of exceptions.
     *
     * <p>{@code BitFramingReg} is set to 7 first: REQA and WUPA are 7-bit
     * frames, and sending eight bits means no card replies at all. The C's
     * length check is reproduced exactly -- anything other than a 16-bit reply
     * is treated as failure, not as a short ATQA.
     */
    public Optional<AnswerToRequest> request(PiccCommand mode) throws NfcException {
        if (mode != PiccCommand.REQUEST_IDLE && mode != PiccCommand.WAKE_UP) {
            throw new IllegalArgumentException("not a request command: " + mode);
        }
        driver.setTransmitLastBits(7);

        PcdResponse response = driver.transceive(
                PcdCommand.TRANSCEIVE, new byte[] {mode.toByte()}, 1);

        if (!response.isOk() || response.bitCount() != ATQA_BIT_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new AnswerToRequest(response.byteAt(0), response.byteAt(1)));
    }

    /**
     * {@code platform_activate_card} minus the RATS: anticollision and SELECT at
     * level 1, then at level 2 if the SAK asks for it.
     *
     * <p>The delays between steps and the order of operations are the C's. So is
     * the decision to run level 2 only when SAK bit {@code 0x04} is set, which
     * is what a 7-byte-UID DESFire does.
     *
     * @throws ActivationException if any step fails; a card that answered REQA
     *                             but cannot be selected is a real fault, unlike
     *                             a card that was never there
     */
    public SelectedCard activate() throws NfcException {
        byte[] cascade1 = anticollision(CascadeLevel.LEVEL_1);
        trace.log(() -> "activate: anticoll cl1 uid=" + Hex.encode(cascade1));
        timebase.sleep(STEP_SETTLE_MS);

        int sak = select(CascadeLevel.LEVEL_1, cascade1);
        trace.log(() -> "activate: select cl1 sak=" + Hex.byteToString(sak));
        timebase.sleep(STEP_SETTLE_MS);

        byte[] cascade2 = null;
        int finalSak = sak;

        if (CascadeLevel.requiresNextLevel(sak)) {
            cascade2 = anticollision(CascadeLevel.LEVEL_2);
            byte[] traced = cascade2;
            trace.log(() -> "activate: anticoll cl2 uid=" + Hex.encode(traced));
            timebase.sleep(STEP_SETTLE_MS);

            finalSak = select(CascadeLevel.LEVEL_2, cascade2);
            int tracedSak = finalSak;
            trace.log(() -> "activate: select cl2 sak=" + Hex.byteToString(tracedSak));
            timebase.sleep(STEP_SETTLE_MS);
        }

        // The C's last delay before RATS. Kept here so the activation sequence
        // hands over a card that has already settled, whatever runs next.
        timebase.sleep(PRE_RATS_SETTLE_MS);

        return new SelectedCard(Uid.fromCascades(cascade1, cascade2), finalSak);
    }

    /**
     * {@code MFRC522_AnticollCascade}: send {@code SEL || NVB=0x20} and read
     * back four UID bytes and their BCC.
     *
     * <p>{@code BitFramingReg} goes back to whole bytes first, undoing the 7-bit
     * framing REQA left behind.
     *
     * <p>The BCC check is the C's: the four bytes XORed together must equal the
     * fifth. It is the only integrity check on the air here -- an anticollision
     * frame carries no CRC -- so a mismatch means either a genuine collision
     * between two cards or a marginal field, and both are worth failing on.
     */
    public byte[] anticollision(CascadeLevel level) throws NfcException {
        driver.setTransmitLastBits(0);

        byte[] request = {level.command().toByte(), CascadeLevel.NVB_ANTICOLLISION};
        PcdResponse response = driver.transceive(PcdCommand.TRANSCEIVE, request, 2);

        if (!response.isOk() || response.dataLength() < CASCADE_REPLY_LENGTH) {
            driver.traceRegisters("activate-anticoll-fail");
            throw new ActivationException(
                    "anticollision at " + level + " failed: status=" + response.status()
                            + " bytes=" + response.dataLength());
        }

        byte[] reply = response.data();
        int checksum = 0;
        for (int i = 0; i < 4; i++) {
            checksum ^= reply[i] & 0xFF;
        }
        if (checksum != (reply[4] & 0xFF)) {
            throw new ActivationException(
                    "anticollision BCC mismatch at " + level + ": got "
                            + Hex.encode(reply, 0, CASCADE_REPLY_LENGTH)
                            + ", expected check byte " + Hex.byteToString(checksum));
        }
        return java.util.Arrays.copyOf(reply, CASCADE_REPLY_LENGTH);
    }

    /**
     * {@code MFRC522_SelectTagCascade}: send {@code SEL || NVB=0x70 || UID || BCC}
     * with a CRC, and read the SAK.
     *
     * <p>The C requires exactly 24 bits back -- SAK plus its two CRC bytes -- and
     * treats a SAK of zero as failure, which is how {@code platform_activate_card}
     * detects a select that did not take. Both are reproduced.
     *
     * <p>Note what this method does <em>not</em> do: it never touches
     * {@code BitFramingReg}. The C does not either, and it works because
     * {@link #anticollision} has just set it to zero. Calling SELECT without a
     * preceding anticollision would transmit a 7-bit final byte and fail
     * silently -- so this is not a method to call on its own.
     */
    public int select(CascadeLevel level, byte[] cascadeReply) throws NfcException {
        byte[] frame = new byte[9];
        frame[0] = level.command().toByte();
        frame[1] = CascadeLevel.NVB_SELECT;
        System.arraycopy(cascadeReply, 0, frame, 2, CASCADE_REPLY_LENGTH);
        crc.appendTo(frame, 7);

        PcdResponse response = driver.transceive(PcdCommand.TRANSCEIVE, frame, 9);

        if (!response.isOk() || response.bitCount() != SAK_BIT_LENGTH) {
            driver.traceRegisters("activate-select-fail");
            throw new ActivationException(
                    "SELECT at " + level + " failed: status=" + response.status()
                            + " bits=" + response.bitCount() + " (expected " + SAK_BIT_LENGTH + ")");
        }

        int sak = response.byteAt(0);
        if (sak == 0) {
            driver.traceRegisters("activate-select-fail");
            throw new ActivationException("SELECT at " + level + " returned SAK=0");
        }
        return sak;
    }

    /**
     * {@code MFRC522_Halt}: send the card to HALT so it stops answering REQA.
     *
     * <p>A halted card is invisible until a WUPA reaches it, which is how a
     * reader avoids re-activating the same card over and over while it sits on
     * the antenna. Like the C, no reply is expected -- a card that answers HALT
     * has not halted -- so the result is discarded.
     */
    public void halt() throws NfcException {
        byte[] frame = new byte[4];
        frame[0] = PiccCommand.HALT.toByte();
        frame[1] = 0x00;
        crc.appendTo(frame, 2);
        driver.transceive(PcdCommand.TRANSCEIVE, frame, 4);
    }
}
