package com.midnightbrewer.reference.pcd.crc;

import com.midnightbrewer.reference.error.NfcException;

/**
 * Computes the two-byte CRC_A that terminates every ISO 14443-3 frame.
 *
 * <p>An interface with two implementations, because the RC522 has a CRC
 * coprocessor and the C uses it exclusively ({@code CalulateCRC}), but the
 * algorithm is also six lines of arithmetic. Keeping both behind one type buys
 * three things: the on-card behaviour stays bit-identical to the firmware,
 * the protocol layers become testable without a coprocessor, and the software
 * version acts as an oracle that proves the hardware path is being driven
 * correctly.
 *
 * <p>Result byte order is CRC-low then CRC-high -- the order the RC522 stores
 * them in {@code CRCResultRegL}/{@code CRCResultRegH}, and the order they go on
 * the air.
 */
public interface CrcCalculator {

    /** Length in bytes of a CRC_A. */
    int CRC_LENGTH = 2;

    /**
     * Computes CRC_A over {@code length} bytes of {@code data} starting at
     * {@code offset}.
     *
     * @return exactly {@value #CRC_LENGTH} bytes, low byte first
     */
    byte[] calculate(byte[] data, int offset, int length) throws NfcException;

    /** Computes CRC_A over the whole array. */
    default byte[] calculate(byte[] data) throws NfcException {
        return calculate(data, 0, data.length);
    }

    /**
     * Appends CRC_A over the first {@code length} bytes of {@code frame} into
     * {@code frame[length]} and {@code frame[length + 1]}.
     *
     * <p>This is the shape every call site in the C uses -- CRC computed over a
     * prefix and written into the same buffer just past it -- so it is worth
     * having as one operation rather than repeating the offset arithmetic.
     */
    default void appendTo(byte[] frame, int length) throws NfcException {
        byte[] crc = calculate(frame, 0, length);
        frame[length] = crc[0];
        frame[length + 1] = crc[1];
    }

    /**
     * True if the last two of {@code length} bytes are a correct CRC_A over the
     * ones before them. Used to find the real end of a frame when the FIFO has
     * delivered trailing rubbish.
     */
    default boolean isValidFrame(byte[] data, int length) throws NfcException {
        if (length < CRC_LENGTH + 1) {
            return false;
        }
        byte[] expected = calculate(data, 0, length - CRC_LENGTH);
        return data[length - 2] == expected[0] && data[length - 1] == expected[1];
    }
}
