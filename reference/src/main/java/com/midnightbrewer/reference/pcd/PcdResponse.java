package com.midnightbrewer.reference.pcd;

import java.util.Arrays;

/**
 * The result of one {@code MFRC522_ToCard} call.
 *
 * <p>The C returns a status byte and writes the bit count through an
 * {@code uint *backLen} out-parameter while the data lands in a caller-supplied
 * array -- three loosely related things a caller must remember to check
 * together. Bundling them means a caller cannot read the data without also
 * having the status and length in hand.
 *
 * <p>The bit count matters and is not redundant. Anticollision and REQA return
 * partial bytes, and the C's callers test it directly: {@code MFRC522_Request}
 * demands exactly {@code 0x10} bits (a two byte ATQA) and
 * {@code MFRC522_SelectTagCascade} demands {@code 0x18} (SAK plus CRC). Those
 * comparisons are reproduced verbatim, so the bit count is preserved as-is.
 *
 * <p>Note the asymmetry inherited from the C, and relied on by the
 * ISO 14443-4 layer: {@link #bitCount()} reflects the <em>whole</em> FIFO level
 * observed at the end of the command, while {@link #data()} holds only the
 * first {@value Rc522Driver#FIFO_READ_MAX} bytes actually drained. The layer
 * above uses the difference to know how much is still sitting in the FIFO.
 */
public final class PcdResponse {

    private final PcdStatus status;
    private final int bitCount;
    private final byte[] data;

    PcdResponse(PcdStatus status, int bitCount, byte[] data) {
        this.status = status;
        this.bitCount = bitCount;
        this.data = data;
    }

    /** A failed exchange with no data. */
    static PcdResponse failure(PcdStatus status) {
        return new PcdResponse(status, 0, new byte[0]);
    }

    public PcdStatus status() {
        return status;
    }

    /** True if the command completed without error bits. */
    public boolean isOk() {
        return status.isOk();
    }

    /**
     * Length of the reply in <em>bits</em>, as computed by the C:
     * {@code (fifoLevel - 1) * 8 + lastBits} when {@code ControlReg} reports a
     * partial final byte, {@code fifoLevel * 8} otherwise.
     */
    public int bitCount() {
        return bitCount;
    }

    /** {@link #bitCount()} rounded up to whole bytes, as {@code (bits + 7) / 8}. */
    public int byteCount() {
        return (bitCount + 7) / 8;
    }

    /** A copy of the bytes drained from the FIFO by this command. */
    public byte[] data() {
        return data.clone();
    }

    /** The number of bytes in {@link #data()}, without copying it. */
    public int dataLength() {
        return data.length;
    }

    /** One byte of the reply, unsigned. */
    public int byteAt(int index) {
        return data[index] & 0xFF;
    }

    /**
     * Copies the drained bytes into {@code destination} at offset zero, writing
     * no more than {@code destination} can hold.
     *
     * <p>The frame assembly buffer in the ISO 14443-4 layer uses this to
     * reproduce the C's habit of writing the command's bytes at offset zero and
     * then setting the frame length from the separately-reported bit count.
     */
    public void copyInto(byte[] destination) {
        System.arraycopy(data, 0, destination, 0, Math.min(data.length, destination.length));
    }

    @Override
    public String toString() {
        return "PcdResponse[" + status + ", " + bitCount + " bits, "
                + Arrays.toString(data) + "]";
    }
}
