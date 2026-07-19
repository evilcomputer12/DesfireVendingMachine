package com.midnightbrewer.card;

/**
 * Sends one APDU and returns one response.
 *
 * <p>This is the boundary between "protocol" and "hardware". Above it,
 * everything is DESFire logic operating on byte arrays. Below it, everything
 * is RC522 registers, FIFO levels and ISO-14443-4 block framing.
 *
 * <p>It is the direct Java counterpart of the {@code df_transceive_fn}
 * function pointer in your C library:
 * <pre>{@code
 * typedef DFStatus (*df_transceive_fn)(void *user, const uint8_t *send,
 *                                      uint8_t send_len, uint8_t *resp,
 *                                      uint8_t *resp_len);
 * }</pre>
 * The C version threads a {@code void *user} through every call to carry
 * context. Java does not need that: an object already carries its own state,
 * so the context is simply the fields of whatever class implements this.
 * That is the whole idea behind an interface plus an implementation.
 *
 * <p><strong>Implementations must handle the I-block chaining themselves.</strong>
 * The RC522 has a 64-byte FIFO and no hardware ISO-DEP support, so anything
 * longer than one frame has to be split, sent with the chaining bit set, and
 * reassembled — exactly what {@code MFRC522_14443P4_Transceive} does in your
 * C code. Callers of this interface must never have to think about that.
 */
public interface Iso14443Transceiver extends AutoCloseable {

    /**
     * Exchange one command APDU for one response APDU.
     *
     * @param apdu command bytes, without any ISO-DEP framing
     * @return response bytes; the DESFire status byte is the <em>first</em>
     *         byte in native wrapping, not the last
     * @throws CardCommunicationException on timeout, CRC error, or card loss
     */
    byte[] transceive(byte[] apdu) throws CardCommunicationException;

    /**
     * Poll for a card, then run anticollision, SELECT and RATS.
     *
     * <p>Mirrors {@code platform_activate_card()} in {@code desfiire.c}.
     *
     * @return the card UID, or {@code null} if no card is in the field
     */
    byte[] pollForCard() throws CardCommunicationException;

    /** Power down the RF field. */
    @Override
    void close();
}
