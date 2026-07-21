package com.midnightbrewer.hardware;

/**
 * M3 — an abstract base for any ISO-14443 reader.
 *
 * STUDY THIS SHAPE -- it's the whole abstract-class lesson.
 *
 * The ISO-14443 protocol (how you wake a card and read its answer) is a
 * standard: identical for an RC522, a PN532, any reader. That standard logic
 * lives HERE, written once. What differs per chip is only ONE thing -- how you
 * physically push a frame out and read the reply. That one thing is left
 * BLANK, as an 'abstract' method, for each chip's subclass to fill.
 *
 * Two keywords make this work:
 *   - 'abstract class' : this class can't be created on its own (no
 *                        'new Iso14443Reader()'), because it has a blank. You
 *                        create a SUBCLASS that fills the blank.
 *   - 'abstract' method: a method with no body -- a promise, like an interface
 *                        method, but sitting inside a class that ALSO has real
 *                        code. That mix (real code + blanks) is exactly what an
 *                        interface cannot do and an abstract class can.
 */
public abstract class Iso14443Reader {

    // ── protocol constants (ISO 14443-3, the same on every reader) ──
    private static final byte REQA = 0x26; // "any IDLE card, announce yourself"
    private static final byte WUPA = 0x52; // "any IDLE *or* HALTed card, wake up"

    /** A valid ATQA answer is exactly two bytes. */
    private static final int ATQA_LENGTH = 2;

    /** REQA/WUPA are 7-bit short frames, not whole bytes. */
    private static final int REQA_BITS = 7;

    // ═════════════════════════════════════════════════════════════════
    // THE BLANK STEP -- the one thing each chip does differently.
    //
    // 'abstract' = declared here, no body. A subclass (Rc522Reader) MUST
    // provide the body, or it won't compile -- same enforcement the interface
    // gave you, but now inside a class that also has real code below.
    //
    //   sendData      : the bytes to transmit
    //   bitsInLastByte: valid bits in the final byte (7 for REQA, 0 = all 8)
    //   returns       : the bytes received, or an EMPTY array if nothing
    //                   answered (a timeout -- normal when no card is present,
    //                   NOT an error).
    // ═════════════════════════════════════════════════════════════════
    protected abstract byte[] transceive(byte[] sendData, int bitsInLastByte)
            throws SpiException;

    // ═════════════════════════════════════════════════════════════════
    // THE TEMPLATE METHOD -- the fixed algorithm, shared by all chips.
    //
    // It is 'final' on purpose: a subclass may fill the blank step, but it may
    // NOT rewrite the sequence. The protocol is the protocol.
    //
    // Notice: this real code calls transceive(), the blank. At runtime that
    // call lands in whichever subclass you actually made -- the base doesn't
    // know or care which chip. (That's polymorphism; it's M4's headline, and
    // you're already using it here.)
    // ═════════════════════════════════════════════════════════════════
    public final boolean isCardPresent() throws SpiException {
        // Try REQA first (IDLE cards); if silence, try WUPA (also wakes HALTed
        // cards). This is the fix you found earlier, now living in the shared
        // base so every reader benefits from it.
        if (request(REQA)) {
            return true;
        }
        return request(WUPA);
    }

    /** Sends one REQA/WUPA and reports whether a well-formed ATQA came back. */
    private boolean request(byte mode) throws SpiException {
        byte[] atqa = transceive(new byte[]{mode}, REQA_BITS);
        return atqa.length == ATQA_LENGTH;
    }
}
