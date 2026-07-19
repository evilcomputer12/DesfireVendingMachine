package com.midnightbrewer.reference.iso14443;

import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.iso14443.block.SupervisoryBlock;
import com.midnightbrewer.reference.pcd.crc.CrcCalculator;

/**
 * A card's request for more time, and the reply that grants it.
 *
 * <p>When a DESFire starts a slow operation -- committing a key change,
 * formatting, writing to EEPROM -- it sends S(WTX) carrying a multiplier, and
 * the reader must echo that multiplier back before waiting. A reader that
 * ignores WTX times out on cards that were about to succeed, and the resulting
 * failure looks exactly like a bad antenna.
 *
 * <p>Both directions live here because they are one negotiation: the multiplier
 * parsed out of the card's frame is the same one that goes into the reply and
 * the same one that scales the wait.
 */
public final class WaitingTimeExtension {

    /**
     * Milliseconds waited per unit of WTXM.
     *
     * <p>A tuned constant from the C's {@code HAL_Delay(bWtxm * 80u)}, not a
     * value from the specification -- ISO 14443-4 derives the real wait from
     * FWT, which this driver never computes. It is generous, which is why it
     * works. Shortening it means going back to the card the reader just
     * promised to wait for.
     */
    public static final int MILLIS_PER_UNIT = 80;

    /** WTXM occupies the low 6 bits of the INF byte; bits 7..6 are RFU. */
    private static final int MULTIPLIER_MASK = 0x3F;

    /** Substituted when a card sends zero, which is out of range. */
    private static final int MINIMUM_MULTIPLIER = 0x01;

    private final int multiplier;

    private WaitingTimeExtension(int multiplier) {
        this.multiplier = multiplier;
    }

    /**
     * Reads the multiplier out of a received S(WTX) frame.
     *
     * <p>The C's three guards are reproduced in its order: a frame shorter than
     * two bytes is treated as a multiplier of 1, only the low 6 bits are kept,
     * and a resulting zero becomes 1. The last two matter -- a zero multiplier
     * would produce a reply the card rejects and a wait of no time at all.
     *
     * @param frame       the received frame, PCB first
     * @param frameLength number of valid bytes in {@code frame}
     */
    public static WaitingTimeExtension parse(byte[] frame, int frameLength) {
        int value = frameLength >= 2 ? (frame[1] & 0xFF) : MINIMUM_MULTIPLIER;
        value &= MULTIPLIER_MASK;
        if (value == 0) {
            value = MINIMUM_MULTIPLIER;
        }
        return new WaitingTimeExtension(value);
    }

    /** The WTXM the card asked for, 1..63. */
    public int multiplier() {
        return multiplier;
    }

    /** How long to wait before going back to the card: {@code WTXM * 80 ms}. */
    public long delayMillis() {
        return (long) multiplier * MILLIS_PER_UNIT;
    }

    /**
     * Writes the S(WTX) reply -- {@code 0xF2 || WTXM || CRC} -- into
     * {@code frame} and returns its length.
     *
     * <p>Writes into a caller-supplied buffer because the C reuses its request
     * array here, and reusing it keeps the allocation behaviour of a loop that
     * can run 32 times identical.
     */
    public int writeReplyInto(byte[] frame, CrcCalculator crc) throws NfcException {
        frame[0] = SupervisoryBlock.waitingTimeExtension().pcb();
        frame[1] = (byte) multiplier;
        crc.appendTo(frame, 2);
        return 4;
    }

    @Override
    public String toString() {
        return "S(WTX) WTXM=" + multiplier + " -> wait " + delayMillis() + " ms";
    }
}
