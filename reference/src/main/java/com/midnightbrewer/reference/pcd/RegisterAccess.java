package com.midnightbrewer.reference.pcd;

import com.midnightbrewer.reference.error.NfcException;

/**
 * Read/modify/write access to the RC522's register file.
 *
 * <p>Narrower than {@link Rc522Driver} on purpose. Collaborators that only need
 * to poke registers -- {@link HardwareCrcCalculator} is the one in this
 * module -- depend on this instead of on the whole driver, so they cannot
 * accidentally start a transceive or reset the field, and they can be tested
 * against a stub register file.
 *
 * <p>{@link #setBitMask} and {@link #clearBitMask} are here rather than being
 * left to callers because a read-modify-write is a single logical operation on
 * a register, and the C's {@code SetBitMask}/{@code ClearBitMask} are used far
 * more often than raw writes.
 */
public interface RegisterAccess {

    /** Reads one register. Returns an unsigned value, 0..255. */
    int read(Register register) throws NfcException;

    /** Writes one register. Only the low 8 bits of {@code value} are used. */
    void write(Register register, int value) throws NfcException;

    /** Sets every bit of {@code mask}, leaving the others alone. {@code SetBitMask}. */
    default void setBitMask(Register register, int mask) throws NfcException {
        write(register, read(register) | mask);
    }

    /** Clears every bit of {@code mask}, leaving the others alone. {@code ClearBitMask}. */
    default void clearBitMask(Register register, int mask) throws NfcException {
        write(register, read(register) & ~mask);
    }
}
