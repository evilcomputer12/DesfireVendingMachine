package com.midnightbrewer.reference.iso14443;

/**
 * The ISO 14443-4 block number toggle.
 *
 * <p>Consecutive I-blocks alternate a single bit so that reader and card can
 * tell a retransmission from a new block. The C keeps it in a file-scope
 * {@code static uint8_t pcb = 0x02} and flips it in {@code get_pcb()}:
 *
 * <pre>
 * static uint8_t get_pcb() {
 *     uint8_t _pcb = pcb;
 *     if (pcb == 0x02) pcb = 0x03; else pcb = 0x02;
 *     return _pcb;
 * }
 * </pre>
 *
 * <p>The values alternate between {@code 0x02} and {@code 0x03}, not {@code 0}
 * and {@code 1}: bit 1 is the reserved bit that ISO 14443-4 requires to be set
 * in every I-block, so it is baked into both. Reproduced exactly, because the
 * card checks it.
 *
 * <p>Making it an object rather than a static removes the property that makes
 * the C version awkward: two readers in one process would have shared one
 * counter. It also makes the sequence testable, which matters -- a toggle that
 * runs backwards produces a card that answers every second block and ignores
 * the rest, and that is a miserable symptom to chase from the RF side.
 *
 * <p>Not thread-safe, deliberately: it belongs to a single card session, and
 * sharing one across threads is already a protocol error.
 */
public final class BlockNumber {

    /**
     * The value both sides start from after RATS. {@code MFRC522_RATS} and
     * {@code MFRC522_FieldReset} both reset the C's counter to this.
     */
    private static final int INITIAL_PCB = 0x02;

    private int nextPcb = INITIAL_PCB;

    /**
     * Returns the PCB for the next outgoing I-block and advances the toggle.
     *
     * <p>The caller ORs in the chaining flag afterwards, exactly as the C does.
     * The toggle advances for every block including chained ones, which is what
     * lets the card's R(ACK) name the block it is acknowledging.
     */
    public byte nextInformationPcb() {
        int current = nextPcb;
        nextPcb = (current == 0x02) ? 0x03 : 0x02;
        return (byte) current;
    }

    /**
     * Restarts the sequence at {@code 0x02}.
     *
     * <p>Called after RATS and after a field reset -- both bring the card up
     * fresh, so its counter is back at zero and the reader's must match. Getting
     * this wrong is the classic cause of "the first command after re-tapping
     * always fails".
     */
    public void reset() {
        nextPcb = INITIAL_PCB;
    }

    /** The PCB that {@link #nextInformationPcb()} would return, without advancing. */
    public int peek() {
        return nextPcb;
    }

    @Override
    public String toString() {
        return String.format("BlockNumber[next PCB=0x%02X]", nextPcb);
    }
}
