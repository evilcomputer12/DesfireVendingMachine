package com.midnightbrewer.reference.iso14443;

import com.midnightbrewer.reference.error.NfcException;

/**
 * An ISO 14443-4 (ISO-DEP, T=CL) link to a card.
 *
 * <p>The interface a card application should be written against. It offers
 * three things -- find a card, bring it up, exchange APDUs -- and hides
 * everything underneath: the block protocol, chaining, waiting-time extensions,
 * the RC522's FIFO, and the fact that there is an RC522 at all.
 *
 * <p>That last point is the reason it is an interface. A DESFire command set
 * written against this type will work unchanged over a PC/SC reader, a
 * different NFC front end, or a recorded transcript in a test. The C has no
 * such boundary: {@code desfiire.c} calls
 * {@code MFRC522_14443P4_Transceive} directly, so every DESFire command in that
 * firmware is welded to this specific chip.
 */
public interface Iso14443Transceiver extends AutoCloseable {

    /**
     * True if a card answers REQA or WUPA right now.
     *
     * <p>Cheap and non-committal -- it does not activate anything -- so it is
     * safe to call in a polling loop. Both request types are tried, because a
     * card halted by a previous transaction answers only WUPA.
     */
    boolean isCardPresent() throws NfcException;

    /**
     * Runs the full activation: anticollision through both cascade levels,
     * SELECT, then RATS.
     *
     * <p>On success the card is in ISO 14443-4 state and
     * {@link #transceive(byte[])} may be called. The block number is reset, so
     * an activation always starts a clean session.
     *
     * @throws com.midnightbrewer.reference.error.ActivationException if any step fails
     */
    ActivatedCard activate() throws NfcException;

    /**
     * Sends one APDU and returns the response.
     *
     * <p>Chaining in both directions, waiting-time extensions and block
     * numbering are handled internally: callers hand over a complete APDU and
     * receive a complete response, however many frames that took.
     *
     * @param apdu the command, with no ISO 14443-4 framing -- no PCB, no CRC
     * @return the response INF, again with no framing
     * @throws com.midnightbrewer.reference.error.ProtocolException if the card
     *         breaks the block protocol
     */
    byte[] transceive(byte[] apdu) throws NfcException;

    /**
     * Sends S(DESELECT), ending the session politely.
     *
     * <p>Best effort. A card that has already left the field cannot answer, and
     * that is not worth failing over.
     */
    void deselect() throws NfcException;

    /**
     * Drops the RF field for {@code offMillis} and brings it back, then clears
     * all protocol state.
     *
     * <p>The recovery of last resort, for a card whose protocol state has
     * diverged from the reader's. Power-cycling it through the air is the only
     * way back.
     */
    void resetField(long offMillis) throws NfcException;

    /** The frame size currently in force, from the last RATS. */
    FrameSize frameSize();

    @Override
    void close() throws NfcException;
}
