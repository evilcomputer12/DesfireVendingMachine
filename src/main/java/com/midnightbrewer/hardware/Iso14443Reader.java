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

    /** Anticollision command, cascade level 1 (ISO 14443-3). */
    private static final byte SEL_CL1 = (byte) 0x93;

    /** Anticollision reply is 4 UID/CT bytes + 1 BCC checksum. */
    private static final int ANTICOLL_LENGTH = 5;

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

    // ═════════════════════════════════════════════════════════════════
    // M5 — anticollision (cascade level 1): read the card's UID.
    //
    // Call this right after isCardPresent() returns true (the card is now in
    // READY and waiting to be identified). It is pure protocol -- it uses your
    // abstract transceive(), so it works on any reader chip.
    //
    //   C:  Write_MFRC522(BitFramingReg, 0x00);   // full bytes -- your
    //                                             //   transceive does this
    //                                             //   with bitsInLastByte = 0
    //       serNum[0] = 0x93;  serNum[1] = 0x20;
    //       MFRC522_ToCard(PCD_TRANSCEIVE, serNum, 2, serNum, &unLen);
    //       // then verify: serNum[0]^serNum[1]^serNum[2]^serNum[3] == serNum[4]
    //
    // Returns the 5-byte reply (4 UID/CT bytes + BCC), or an empty array if
    // nothing valid came back.
    // ═════════════════════════════════════════════════════════════════
    public byte[] anticollision() throws SpiException {
        // TODO 1: send { SEL_CL1, 0x20 } with FULL-byte framing (bits = 0):
        //         byte[] resp = transceive(new byte[]{SEL_CL1, 0x20}, 0);
        byte[] resp = transceive(new byte[]{SEL_CL1, 0x20}, 0);

        // TODO 2: a valid reply is exactly ANTICOLL_LENGTH bytes.
        //         If not, return new byte[0].
        if(resp.length != ANTICOLL_LENGTH) {
            return new byte[0];
        }

        // TODO 3: verify the BCC. XOR the first four bytes together; the result
        //         must equal the fifth (resp[4]). If it doesn't, the read was
        //         garbled -> return new byte[0].
        //         (byte bcc = (byte)(resp[0]^resp[1]^resp[2]^resp[3]); ...)
        byte bcc = (byte)(resp[0]^resp[1]^resp[2]^resp[3]);
        if(bcc != resp[4]) {
            return new byte[0];
        }

        // TODO 4: return resp.
        return resp;
    }
}
