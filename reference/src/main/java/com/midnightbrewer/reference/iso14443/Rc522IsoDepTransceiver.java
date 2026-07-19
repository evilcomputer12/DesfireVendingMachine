package com.midnightbrewer.reference.iso14443;

import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.ActivationException;
import com.midnightbrewer.reference.error.CardNotPresentException;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.error.ProtocolException;
import com.midnightbrewer.reference.iso14443.block.InformationBlock;
import com.midnightbrewer.reference.iso14443.block.ProtocolBlock;
import com.midnightbrewer.reference.iso14443.block.ReceiveReadyBlock;
import com.midnightbrewer.reference.iso14443.block.SupervisoryBlock;
import com.midnightbrewer.reference.pcd.PcdCommand;
import com.midnightbrewer.reference.pcd.PcdResponse;
import com.midnightbrewer.reference.pcd.PcdStatus;
import com.midnightbrewer.reference.pcd.Rc522Driver;
import com.midnightbrewer.reference.pcd.Register;
import com.midnightbrewer.reference.pcd.ResponseTimeout;
import com.midnightbrewer.reference.pcd.crc.CrcCalculator;
import com.midnightbrewer.reference.picc.PiccActivator;
import com.midnightbrewer.reference.picc.SelectedCard;
import com.midnightbrewer.reference.util.Hex;
import com.midnightbrewer.reference.util.Timebase;

import java.util.Objects;

/**
 * ISO 14443-4 over an RC522: the port of {@code MFRC522_RATS},
 * {@code MFRC522_14443P4_Transceive}, {@code MFRC522_14443P4_Deselect} and the
 * static helpers they depend on.
 *
 * <p>This is the layer the whole port exists for, and the one where mistakes
 * show up first, so the wire behaviour is reproduced without editorial. Where
 * the C retries three times, this retries three times; where it waits 80 ms per
 * WTX unit, so does this; where it caps outgoing INF at 40 bytes regardless of
 * what the card negotiated, so does this. Each such value carries a comment
 * saying what it is for, and the handful of places where an exact reproduction
 * was impossible or actively harmful are called out individually.
 *
 * <p>Structurally it is composition throughout: an {@link Rc522Driver} for the
 * chip, a {@link PiccActivator} for part 3, a {@link BlockNumber} for the
 * toggle, a {@link ReceiveBuffer} for frame assembly. The C's four file-scope
 * statics -- {@code pcb}, {@code g_iso_fsc}, {@code res}, {@code resSize} --
 * become fields of one session object, so two readers in one process no longer
 * corrupt each other's protocol state.
 *
 * <p>Not thread-safe: one card conversation at a time, which is a property of
 * the radio and not just of this code.
 */
public final class Rc522IsoDepTransceiver implements Iso14443Transceiver {

    /**
     * Hard cap on outgoing INF, from {@code MFRC522_P4_MAX_INF_TX}.
     *
     * <p>Below what most cards negotiate, and applied on top of the card's own
     * limit. The C's comment explains the sizing: it keeps a 47-byte ChangeKey
     * APDU chained while letting 37- and 38-byte WriteData and Authenticate
     * frames go out whole. It also happens to mask the FSCI misreading described
     * in {@link AnswerToSelect} -- an overstated FSC never reaches the air,
     * because this cap is what binds.
     */
    private static final int MAX_TRANSMIT_INFORMATION_BYTES = 40;

    /**
     * Wall-clock budget for the FIFO settle loop, from {@code p4_wait_rx_complete}.
     *
     * <p><strong>This loop costs its full budget on every frame whose reply was
     * already fully drained</strong>, which is the common case: the RC522 raises
     * RxIRq once a complete frame is in the FIFO, {@code MFRC522_ToCard} drains
     * all of it, and this loop then waits for a level that will never rise
     * again. It is reproduced exactly because it is what the working firmware
     * does, and because shortening it would break the case it was written for --
     * a command that ended on IdleIRq while the frame was still arriving, where
     * the reply is assembled entirely by this loop and the drain that follows.
     *
     * <p>If APDU throughput turns out to be the problem on the Pi, this constant
     * is the first thing to look at, and the fix is to exit early when the
     * command already reported a complete frame -- not to lower the budget.
     */
    private static final long FIFO_SETTLE_BUDGET_MS = 3000L;

    /** Poll interval inside the settle loop, from the C's {@code HAL_Delay(1)}. */
    private static final long FIFO_SETTLE_POLL_MS = 1L;

    /** Frame length below which the C does not attempt a CRC trim. */
    private static final int CRC_TRIM_MINIMUM_LENGTH = 8;

    /** Shortest frame that can carry a CRC: PCB plus two CRC bytes, plus one INF byte. */
    private static final int MINIMUM_TRIMMED_LENGTH = 4;

    /**
     * Iterations of the post-final-block receive loop, from the C's
     * {@code retries = 24}. Bounds the loop independently of the WTX budget.
     */
    private static final int RESPONSE_LOOP_LIMIT = 24;

    /**
     * Waiting-time extensions granted before giving up, from
     * {@code wtx_left = 32}. At 80 ms a unit this is a generous ceiling; it
     * exists so a card stuck in a WTX loop cannot hold the reader forever.
     */
    private static final int WTX_GRANT_LIMIT = 32;

    /**
     * RATS PARAM candidates, tried in order: FSDI 8 (FSD 256), then 5 (64),
     * then 2 (32), each with CID 0.
     *
     * <p>The C's comment says it requests FSDI=8 first "like the CLRC663
     * reference, then falls back". Cards that dislike a large FSD refuse the
     * first and answer the second or third, so the order is part of the
     * compatibility story and is preserved.
     */
    private static final int[] RATS_PARAM_CANDIDATES = {0x80, 0x50, 0x20};

    /** RATS frame length: {@code E0 || PARAM || CRC}. */
    private static final int RATS_FRAME_LENGTH = 4;

    /** The RATS command byte. */
    private static final byte RATS_COMMAND = (byte) 0xE0;

    /** Pause between RATS attempts, from {@code HAL_Delay(50)}. */
    private static final long RATS_RETRY_DELAY_MS = 50L;

    /** Shortest RATS reply the C accepts before reading the frame size from it. */
    private static final int MINIMUM_ATS_LENGTH = 2;

    /**
     * SW1 of a DESFire ISO-wrapped response. Used to find the true end of the
     * INF field when the FIFO has delivered trailing rubbish.
     */
    private static final int DESFIRE_STATUS_MARKER = 0x91;

    /** Request buffer size, from the C's {@code uint8_t req[260]}. */
    private static final int REQUEST_CAPACITY = 260;

    private final Rc522Driver driver;
    private final PiccActivator activator;
    private final CrcCalculator crc;
    private final Timebase timebase;
    private final ProtocolTrace trace;

    private final BlockNumber blockNumber = new BlockNumber();
    private final ReceiveBuffer receiveBuffer = new ReceiveBuffer();
    private final byte[] requestBuffer = new byte[REQUEST_CAPACITY];

    /** Replaces the C's {@code static uint16_t g_iso_fsc = 64}. */
    private FrameSize frameSize = FrameSize.DEFAULT;

    public Rc522IsoDepTransceiver(Rc522Driver driver) {
        this(driver, new PiccActivator(driver));
    }

    /** Constructor for tests that want to supply their own activator. */
    public Rc522IsoDepTransceiver(Rc522Driver driver, PiccActivator activator) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.activator = Objects.requireNonNull(activator, "activator");
        this.crc = driver.crc();
        this.timebase = driver.timebase();
        this.trace = driver.trace();
    }

    // -------------------------------------------------------------- activation

    @Override
    public boolean isCardPresent() throws NfcException {
        return activator.isCardPresent();
    }

    /**
     * The full {@code platform_activate_card} sequence: part 3 activation
     * followed by RATS.
     */
    @Override
    public ActivatedCard activate() throws NfcException {
        SelectedCard selected = activator.activate();
        AnswerToSelect ats = requestAnswerToSelect();
        return new ActivatedCard(selected, ats);
    }

    /**
     * {@code MFRC522_RATS}: request ISO 14443-4 mode, trying three FSDI values.
     *
     * <p>Deliberately does <em>not</em> reuse {@link #exchangeFrame}. The C's
     * RATS path drains the FIFO but skips the CRC-based trim that every later
     * exchange performs, and an ATS is short enough that the trim would be
     * scanning for a boundary it cannot reliably find. Reproducing that means
     * writing the sequence out again rather than sharing the helper.
     *
     * <p>On success the block number is reset, because the card's counter is
     * back at zero and the two must agree from the first I-block.
     */
    public AnswerToSelect requestAnswerToSelect() throws NfcException {
        for (int attempt = 0; attempt < RATS_PARAM_CANDIDATES.length; attempt++) {
            int param = RATS_PARAM_CANDIDATES[attempt];

            byte[] request = new byte[RATS_FRAME_LENGTH];
            request[0] = RATS_COMMAND;
            request[1] = (byte) param;
            crc.appendTo(request, 2);

            receiveBuffer.clear();

            final int attemptNumber = attempt + 1;
            trace.frame("RATS attempt " + attemptNumber + " >", request, RATS_FRAME_LENGTH);

            PcdResponse response =
                    driver.transceive(PcdCommand.TRANSCEIVE, request, RATS_FRAME_LENGTH);
            if (response.isOk()) {
                awaitFifoSettled();
            }
            receiveBuffer.acceptCommandData(response);
            if (response.isOk()) {
                drainFifo();
            }

            trace.log(() -> "RATS try " + attemptNumber + ": status=" + response.status()
                    + " len=" + receiveBuffer.length()
                    + " param=" + Hex.byteToString(param));

            if (response.isOk() && receiveBuffer.length() >= MINIMUM_ATS_LENGTH) {
                AnswerToSelect ats =
                        AnswerToSelect.parse(receiveBuffer.array(), receiveBuffer.length());
                frameSize = ats.frameSize();
                blockNumber.reset();
                trace.log(() -> "RATS OK: ATS=" + ats);
                return ats;
            }

            driver.traceRegisters("rats-fail");
            // Give the card time to settle before asking again; a card that just
            // refused an FSDI is mid-transition and answers the next frame badly.
            timebase.sleep(RATS_RETRY_DELAY_MS);
        }

        throw new ActivationException(
                "RATS failed for all " + RATS_PARAM_CANDIDATES.length + " FSDI candidates");
    }

    // --------------------------------------------------------------- exchanges

    /**
     * {@code MFRC522_14443P4_Transceive}: send an APDU, chaining if it does not
     * fit, and return the response.
     *
     * <p>The sequence, unchanged from the C:
     *
     * <ol>
     *   <li>compute the outgoing INF limit as the smaller of the card's
     *       {@code FSC - 3} and this driver's own 40-byte cap;</li>
     *   <li>install the 2 s NV-write timeout for the whole exchange;</li>
     *   <li>send I-blocks, taking a fresh PCB from the block-number toggle for
     *       each and setting the chaining bit on every block but the last;</li>
     *   <li>after each chained block, require an R(ACK) before continuing;</li>
     *   <li>after the final block, accept an I-block as the response, or an
     *       S(WTX) which is answered and then waited on;</li>
     *   <li>restore the 300 ms timeout, whatever happened.</li>
     * </ol>
     *
     * <p>The timeout restore is in a {@code finally}, which the C achieves by
     * having a single exit path. Same effect, and it survives the exceptions
     * this version throws where the C returned a status code.
     */
    @Override
    public byte[] transceive(byte[] apdu) throws NfcException {
        Objects.requireNonNull(apdu, "apdu");
        if (apdu.length == 0) {
            throw new IllegalArgumentException("APDU is empty");
        }

        int maxInformation = frameSize.maxInformationBytes();
        if (maxInformation > MAX_TRANSMIT_INFORMATION_BYTES) {
            maxInformation = MAX_TRANSMIT_INFORMATION_BYTES;
        }
        final int informationLimit = maxInformation;

        receiveBuffer.clear();
        driver.applyTimeout(ResponseTimeout.NV_WRITE);

        try {
            trace.log(() -> "TX APDU len=" + apdu.length + " " + frameSize
                    + " txMaxINF=" + informationLimit
                    + (apdu.length <= informationLimit ? " single_frame" : " chaining"));

            int offset = 0;
            int blockCount = 0;

            while (offset < apdu.length) {
                int chunk = apdu.length - offset;
                boolean more = chunk > informationLimit;
                blockCount++;
                if (more) {
                    chunk = informationLimit;
                }

                InformationBlock block =
                        InformationBlock.of(blockNumber.nextInformationPcb()).withChaining(more);

                requestBuffer[ProtocolBlock.PCB_POSITION] = block.pcb();
                System.arraycopy(apdu, offset, requestBuffer, 1, chunk);
                crc.appendTo(requestBuffer, chunk + 1);
                int requestLength = 1 + chunk + CrcCalculator.CRC_LENGTH;

                final int tracedBlock = blockCount;
                final int tracedChunk = chunk;
                final int tracedOffset = offset;
                final boolean tracedMore = more;
                trace.log(() -> "I-block #" + tracedBlock + ": PCB=" + Hex.byteToString(block.pcb())
                        + " INF=" + tracedChunk + (tracedMore ? " CHAIN" : " FINAL")
                        + " offset=" + tracedOffset);
                trace.frame("I-block >", requestBuffer, requestLength);

                PcdStatus status = exchangeFrame(requestBuffer, requestLength);
                if (!status.isOk()) {
                    driver.traceRegisters("p4-tx-fail");
                    throw failureFor(status, "I-block exchange failed");
                }

                ProtocolBlock received = receivedBlock();
                trace.log(() -> "RX frame len=" + receiveBuffer.length() + " " + received);

                if (more) {
                    // A chained block must be acknowledged before the next one
                    // goes out. Anything else means the card lost the chain, and
                    // the C abandons rather than trying to resynchronise.
                    if (!(received instanceof ReceiveReadyBlock r) || !r.isAcknowledgement()) {
                        throw new ProtocolException(
                                "expected R(ACK) after chained block, got " + received);
                    }
                    offset += chunk;
                    continue;
                }

                // Final block: the response, or a request for more time first.
                if (received instanceof InformationBlock) {
                    return extractInformationField();
                }
                if (received instanceof SupervisoryBlock s && s.isWaitingTimeExtension()) {
                    WaitingTimeExtension wtx =
                            WaitingTimeExtension.parse(receiveBuffer.array(), receiveBuffer.length());
                    int replyLength = wtx.writeReplyInto(requestBuffer, crc);
                    trace.log(() -> "first " + wtx);
                    timebase.sleep(wtx.delayMillis());
                    return receiveResponse(requestBuffer, replyLength);
                }
                throw new ProtocolException("unexpected reply after final block: " + received);
            }

            throw new ProtocolException("APDU produced no blocks");
        } finally {
            driver.applyTimeout(ResponseTimeout.DEFAULT);
        }
    }

    /**
     * {@code p4_recv_apdu_response}: keep exchanging until an I-block arrives,
     * answering each S(WTX) along the way.
     *
     * <p>Two independent limits, both the C's: {@link #RESPONSE_LOOP_LIMIT}
     * iterations and {@link #WTX_GRANT_LIMIT} granted extensions.
     *
     * <p><strong>One deliberate deviation.</strong> When the C's loop runs out of
     * iterations it falls through holding the last {@code MI_OK} from a
     * successful S(WTX) exchange, so it reports success with an empty response --
     * a caller then fails somewhere further along with a misleading message. This
     * throws instead. Nothing on the air differs; only the diagnosis does.
     */
    private byte[] receiveResponse(byte[] request, int requestLength) throws NfcException {
        int loopsLeft = RESPONSE_LOOP_LIMIT;
        int wtxLeft = WTX_GRANT_LIMIT;
        int currentLength = requestLength;

        do {
            trace.frame("S(WTX) >", request, currentLength);

            PcdStatus status = exchangeFrame(request, currentLength);
            if (!status.isOk()) {
                throw failureFor(status, "waiting for APDU response");
            }

            ProtocolBlock received = receivedBlock();
            trace.log(() -> "RX frame len=" + receiveBuffer.length() + " " + received);

            if (received instanceof InformationBlock) {
                return extractInformationField();
            }
            if (received instanceof SupervisoryBlock s && s.isWaitingTimeExtension()) {
                if (wtxLeft-- == 0) {
                    throw new ProtocolException(
                            "card requested more than " + WTX_GRANT_LIMIT
                                    + " waiting-time extensions");
                }
                WaitingTimeExtension wtx =
                        WaitingTimeExtension.parse(receiveBuffer.array(), receiveBuffer.length());
                currentLength = wtx.writeReplyInto(request, crc);
                trace.log(wtx::toString);
                timebase.sleep(wtx.delayMillis());
                continue;
            }
            throw new ProtocolException("unexpected reply while awaiting response: " + received);
        } while (loopsLeft-- != 0);

        throw new ProtocolException(
                "no I-block after " + RESPONSE_LOOP_LIMIT + " exchanges");
    }

    /**
     * {@code p4_exchange_frame}: one T=CL frame out, one frame assembled back in.
     *
     * <p>Order matters and is the C's: run the command, let the FIFO settle,
     * take the length from the command's bit count, drain whatever is left, and
     * only then consider trimming.
     */
    private PcdStatus exchangeFrame(byte[] request, int requestLength) throws NfcException {
        PcdResponse response = driver.transceive(PcdCommand.TRANSCEIVE, request, requestLength);

        if (response.isOk()) {
            awaitFifoSettled();
        }
        receiveBuffer.acceptCommandData(response);

        if (response.isOk()) {
            drainFifo();
            if (receiveBuffer.length() > CRC_TRIM_MINIMUM_LENGTH) {
                trimToValidFrame();
            }
        }
        return response.status();
    }

    /**
     * {@code p4_wait_rx_complete}: wait for the FIFO level to stop changing.
     *
     * <p>Two consecutive reads one millisecond apart must agree before the frame
     * is considered complete. See {@link #FIFO_SETTLE_BUDGET_MS} for the cost of
     * this loop in the common case -- it is reproduced as written.
     */
    private void awaitFifoSettled() throws NfcException {
        long start = timebase.millis();
        int previousLevel = 0xFF;

        while (timebase.millis() - start < FIFO_SETTLE_BUDGET_MS) {
            int level = driver.fifoLevel();
            if (level > 0) {
                if (level == previousLevel) {
                    timebase.sleep(FIFO_SETTLE_POLL_MS);
                    if (driver.fifoLevel() == level) {
                        return;
                    }
                }
                previousLevel = level;
            }
            timebase.sleep(FIFO_SETTLE_POLL_MS);
        }
    }

    /**
     * {@code p4_read_rx_fifo}: append everything still in the FIFO to the frame.
     *
     * <p>Appends at the current length, which the bit count set -- not at the
     * number of bytes the command copied. See {@link ReceiveBuffer} for why
     * those differ.
     */
    private void drainFifo() throws NfcException {
        int leftover = driver.fifoLevel();
        while (leftover-- > 0 && !receiveBuffer.isFull()) {
            receiveBuffer.append(driver.readFifoByte());
        }
    }

    /**
     * {@code p4_frame_len_by_crc}: find the real end of the frame when the FIFO
     * delivered more than one frame's worth.
     *
     * <p>Scans downward from the full length and takes the <em>longest</em>
     * prefix ending in a valid CRC. The C's comment explains why longest rather
     * than first: encrypted DESFire payload can contain a shorter prefix whose
     * trailing bytes happen to form a valid CRC, and stopping at the first match
     * would truncate a good frame.
     *
     * <p>In the normal case the full length is already valid and this costs one
     * CRC computation.
     */
    private void trimToValidFrame() throws NfcException {
        int length = receiveBuffer.length();
        if (length < MINIMUM_TRIMMED_LENGTH) {
            return;
        }
        for (int end = length; end >= MINIMUM_TRIMMED_LENGTH; end--) {
            if (crc.isValidFrame(receiveBuffer.array(), end)) {
                if (end != length) {
                    final int trimmed = end;
                    trace.log(() -> "RX CRC trim " + length + " -> " + trimmed);
                    receiveBuffer.truncateTo(end);
                }
                return;
            }
        }
        // No valid frame found: the C leaves the length alone and logs, and so
        // does this. The block layer above will usually reject it anyway.
        trace.log(() -> {
            try {
                return "RX no CRC trim match len=" + length
                        + " Status2=" + Hex.byteToString(driver.read(Register.STATUS2));
            } catch (NfcException e) {
                return "RX no CRC trim match len=" + length;
            }
        });
    }

    /**
     * Classifies the PCB of the assembled frame.
     *
     * <p>Reads byte zero unconditionally, even when the frame is empty. The C
     * does the same -- it indexes a {@code memset} buffer -- so an empty reply
     * is seen as an I-block with PCB {@code 0x00} and yields an empty response
     * rather than an error. Preserved so that the failure mode does not move.
     */
    private ProtocolBlock receivedBlock() {
        return ProtocolBlock.of(receiveBuffer.byteAt(ProtocolBlock.PCB_POSITION));
    }

    /**
     * {@code p4_copy_inf_apdu} together with {@code p4_inf_field}: pull the INF
     * field out of a {@code PCB [CID] INF CRC} frame.
     */
    private byte[] extractInformationField() {
        int frameLength = receiveBuffer.length();
        if (frameLength < 3) {
            return new byte[0];
        }

        int infOffset = 1;
        int infLength = frameLength - 3;

        // A CID byte sits between the PCB and INF. This driver never sends one,
        // but a card may still include it, and ignoring it would shift the whole
        // payload by a byte.
        if ((receiveBuffer.byteAt(ProtocolBlock.PCB_POSITION) & 0x08) != 0 && infLength > 0) {
            infOffset = 2;
            infLength--;
        }
        if (infLength == 0) {
            return new byte[0];
        }

        infLength = desfireInformationLength(infOffset, infLength);
        return receiveBuffer.copyRange(infOffset, infLength);
    }

    /**
     * {@code p4_desfire_inf_len}: trim FIFO rubbish after a DESFire response.
     *
     * <p>An ISO-wrapped DESFire reply ends with {@code 91 XX}, so the last
     * {@code 0x91} that has a byte after it marks the end. Scanning backwards is
     * deliberate and the C says why: encrypted payload can contain {@code 0x91}
     * anywhere, so the <em>last</em> occurrence is the status marker and an
     * earlier one is data.
     *
     * <p>This is DESFire-specific logic sitting in a generic ISO 14443-4 path.
     * It is here because it is there in the C, and because removing it would let
     * trailing rubbish through to the card layer.
     */
    private int desfireInformationLength(int offset, int infLength) {
        if (infLength < 2) {
            return infLength;
        }
        for (int i = infLength - 2; i > 0; i--) {
            if (receiveBuffer.byteAt(offset + i) == DESFIRE_STATUS_MARKER) {
                return i + 2;
            }
        }
        if (receiveBuffer.byteAt(offset) == DESFIRE_STATUS_MARKER) {
            return 2;
        }
        return infLength;
    }

    // ------------------------------------------------------------ housekeeping

    /**
     * {@code MFRC522_14443P4_Deselect}: send S(DESELECT).
     *
     * <p>Like the C, the reply is not assembled or checked -- a single
     * transceive, result discarded. The session is over either way.
     */
    @Override
    public void deselect() throws NfcException {
        byte[] request = new byte[3];
        request[ProtocolBlock.PCB_POSITION] = SupervisoryBlock.deselect().pcb();
        crc.appendTo(request, 1);

        trace.frame("S(DESELECT) >", request, request.length);
        driver.transceive(PcdCommand.TRANSCEIVE, request, request.length);
    }

    /**
     * {@code MFRC522_FieldReset}: power-cycle the field and clear all protocol
     * state.
     *
     * <p>The register and antenna work belongs to the driver; the two pieces of
     * ISO 14443-4 state the C resets alongside it -- the block number and the
     * frame size -- belong here. Resetting the field without resetting them
     * would leave the reader expecting a block number the freshly-woken card
     * knows nothing about.
     */
    @Override
    public void resetField(long offMillis) throws NfcException {
        driver.fieldReset(offMillis);
        blockNumber.reset();
        frameSize = FrameSize.DEFAULT;
    }

    /** {@code MFRC522_GetIsoFsc}. */
    @Override
    public FrameSize frameSize() {
        return frameSize;
    }

    /** The part 3 activator, for callers that need HALT or a raw anticollision. */
    public PiccActivator activator() {
        return activator;
    }

    /** The underlying chip, for diagnostics. */
    public Rc522Driver driver() {
        return driver;
    }

    @Override
    public void close() throws NfcException {
        driver.close();
    }

    /**
     * Turns a failed exchange into the right exception.
     *
     * <p>{@code NO_TAG} means the card stopped answering, which is a different
     * problem from a card that answered wrongly, and the two lead to different
     * fixes. The C collapses both into {@code MI_ERR} at this point and loses
     * the distinction.
     */
    private NfcException failureFor(PcdStatus status, String context) {
        if (status == PcdStatus.NO_TAG) {
            return new CardNotPresentException(context + ": card stopped responding");
        }
        return new ProtocolException(context + ": status=" + status);
    }
}
