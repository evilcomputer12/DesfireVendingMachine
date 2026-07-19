package com.midnightbrewer.reference.spi;

import com.midnightbrewer.reference.error.TransportException;

/**
 * A full-duplex SPI link: the single seam between this driver and hardware.
 *
 * <p>The interface knows nothing about the RC522. It has exactly one operation
 * plus a close, and it names no register, no command and no protocol. That is
 * the point -- everything above it is ordinary Java that runs anywhere, and the
 * only code that needs a Raspberry Pi under it is {@link Pi4jSpiLink}.
 *
 * <p>The C driver has no such seam. {@code Write_MFRC522} calls
 * {@code HAL_SPI_TransmitReceive} and toggles a GPIO directly, so there is no
 * way to exercise the ISO 14443-4 state machine without a board, a card and an
 * oscilloscope. Replacing that one call with an interface is what makes the
 * chaining and WTX logic in this module unit-testable.
 */
public interface SpiLink extends AutoCloseable {

    /**
     * Clocks {@code length} bytes out of {@code tx} while clocking the same
     * number of bytes into {@code rx}.
     *
     * <p>SPI is inherently full duplex: byte <em>n</em> arrives while byte
     * <em>n</em> is being sent, so the reply to an address byte is always in
     * the <em>following</em> position, never the same one. Callers must size
     * their buffers with that shift in mind.
     *
     * <p>Chip select is asserted for the whole call and released at the end.
     * The C driver's comment on {@code Write_MFRC522} explains why this must be
     * one call and not two: two back-to-back single-byte transfers let CS glitch
     * high between them, and the RC522 aborts the transaction.
     *
     * @param tx     bytes to clock out; not modified
     * @param rx     buffer that receives the bytes clocked in; overwritten
     * @param length number of bytes to exchange
     * @throws TransportException       if the underlying device failed
     * @throws IllegalArgumentException if either buffer is shorter than {@code length}
     */
    void transfer(byte[] tx, byte[] rx, int length) throws TransportException;

    /**
     * Releases the device. Idempotent: closing twice is not an error.
     */
    @Override
    void close() throws TransportException;
}
