package com.midnightbrewer.reference.support;

/**
 * A card, as seen from inside the simulated RC522.
 *
 * <p>The RF layer reduces to one operation: a frame goes out, a frame comes
 * back, or nothing does. Everything a test wants to vary -- a card that is not
 * there, a card with a 4-byte UID, a card that asks for extra time, a card that
 * breaks the chaining rules -- is an implementation of this.
 */
@FunctionalInterface
public interface SimulatedPicc {

    /**
     * Handles one frame from the reader.
     *
     * @param request     the transmitted bytes
     * @param txLastBits  {@code BitFramingReg[2..0]}: 0 for whole bytes, 7 for
     *                    the short frames REQA and WUPA use
     * @return the reply, or {@code null} to stay silent so the reader's
     *         hardware timer expires
     */
    byte[] exchange(byte[] request, int txLastBits);

    /** A card that never answers anything. */
    static SimulatedPicc silent() {
        return (request, txLastBits) -> null;
    }
}
