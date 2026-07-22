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

    /** Anticollision/SELECT command bytes for the two cascade levels. */
    private static final byte SEL_CL1 = (byte) 0x93;
    private static final byte SEL_CL2 = (byte) 0x95;

    /** NVB byte that turns an anticollision into a SELECT (full UID follows). */
    private static final byte SELECT_NVB = 0x70;

    /** SAK bit 3: "the UID is not complete, do the next cascade level". */
    private static final int CASCADE_BIT = 0x04;

    /** RATS: request ISO-14443-4. PARAM 0x50 = FSDI 5 (our frame size), CID 0. */
    private static final byte RATS_CMD = (byte) 0xE0;
    private static final byte RATS_PARAM = 0x50;

    /** The cascade tag that prefixes a 7-byte UID's first anticollision reply. */
    private static final byte CASCADE_TAG = (byte) 0x88;

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

    /**
     * The SECOND chip-specific step: compute the 2-byte CRC_A over {@code data}.
     *
     * SELECT and RATS require a CRC appended to the frame; anticollision did
     * not. The RC522 has a hardware CRC unit, but another reader might compute
     * it differently, so -- exactly like transceive -- it's left abstract for
     * the chip's subclass to provide. An abstract class can carry as many
     * blanks as the design needs.
     *
     * @return the two CRC bytes (low byte first, then high), ready to append.
     */
    protected abstract byte[] calculateCrc(byte[] data) throws SpiException;

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
    public byte[] anticollision(byte selCode) throws SpiException {
        // Now takes selCode so it works at BOTH levels: SEL_CL1 (0x93) reads the
        // first UID bytes, SEL_CL2 (0x95) reads the rest of a 7-byte UID.
        byte[] resp = transceive(new byte[]{selCode, 0x20}, 0);

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

    // ═════════════════════════════════════════════════════════════════
    // M5 — SELECT: send the UID back, get the card's SAK.
    //
    //   C:  buffer[0]=selCode; buffer[1]=0x70;         // SELECT, not anticoll
    //       for(i=0;i<5;i++) buffer[i+2] = anticoll[i]; // the 4 UID + BCC
    //       CalulateCRC(buffer, 7, &buffer[7]);         // 2 CRC bytes -> 9 total
    //       ToCard(...);  // reply = SAK(1) + CRC(2) = 3 bytes (recvBits 0x18)
    //       SAK = buffer[0];
    //
    // @param anticoll the 5-byte anticollision reply (4 UID/CT bytes + BCC)
    // @return the SAK byte (0..255), or -1 if the reply was malformed
    // ═════════════════════════════════════════════════════════════════
    public int select(byte selCode, byte[] anticoll) throws SpiException {
        // TODO 1: build a 7-byte frame: { selCode, SELECT_NVB, then the 5
        //         bytes of anticoll }. (Make a byte[7]; set [0],[1]; then
        //         System.arraycopy(anticoll, 0, frame, 2, 5); )
        byte[] frame7 = new byte[7];
        frame7[0] = selCode;
        frame7[1] = SELECT_NVB;
        System.arraycopy(anticoll, 0, frame7, 2, 5);
        // TODO 2: byte[] crc = calculateCrc(frame7);   // your CRC method
        //         Make a byte[9]: the 7 frame bytes then crc[0], crc[1].
        //         (arraycopy the 7 in, then set [7]=crc[0], [8]=crc[1])
        byte[] crc = calculateCrc(frame7);
        byte[] frame9 = new byte[9];
        System.arraycopy(frame7, 0, frame9, 0, 7);
        frame9[7] = crc[0];
        frame9[8] = crc[1];
        // TODO 3: byte[] reply = transceive(frame9, 0);   // full bytes
        byte[] reply = transceive(frame9, 0);
        // TODO 4: a good reply is 3 bytes (SAK + 2 CRC). If reply.length < 1
        //         return -1; else return reply[0] & 0xFF;
        if(reply.length < 1) {
            return -1;
        } else{
            return reply[0] & 0xFF;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // M5 — RATS: ask to speak ISO-14443-4; the card returns its ATS.
    //
    //   C:  req[0]=0xE0; req[1]=0x50; CalulateCRC(req,2,&req[2]); ToCard(...)
    //
    // @return the ATS bytes (empty array if the card refused)
    // ═════════════════════════════════════════════════════════════════
    public byte[] rats() throws SpiException {
        // TODO 1: byte[] header = { RATS_CMD, RATS_PARAM };
        byte[] header = {RATS_CMD, RATS_PARAM};
        // TODO 2: byte[] crc = calculateCrc(header);
        //         byte[] frame = { RATS_CMD, RATS_PARAM, crc[0], crc[1] };
        byte[] crc = calculateCrc(header);
        byte[] frame = {RATS_CMD, RATS_PARAM, crc[0], crc[1]};
        
        // TODO 3: return transceive(frame, 0);
        return transceive(frame, 0);
    }

    // ═════════════════════════════════════════════════════════════════
    // M5 — activate(): the whole sequence, as a TEMPLATE METHOD.
    //
    // 'final' so a subclass can't reorder the protocol. It orchestrates the
    // steps you built:
    //   level 1: anticollision -> select -> SAK
    //   if the SAK's cascade bit is set, the UID is 7 bytes: do level 2 too
    //   then RATS
    // and assembles the real UID:
    //   - a 4-byte card: just anticoll1[0..3]
    //   - a 7-byte card: anticoll1[1..3]  (skip the 0x88 cascade tag)
    //                    + anticoll2[0..3]
    // ═════════════════════════════════════════════════════════════════
    public final ActivatedCard activate() throws SpiException {
        // TODO 1: byte[] ac1 = anticollision(SEL_CL1);
        //         if (ac1.length == 0) throw new SpiException("anticollision CL1 failed");
        //         int sak1 = select(SEL_CL1, ac1);
        byte[] ac1 = anticollision(SEL_CL1);
        if(ac1.length == 0){
            throw new SpiException("anticollision CL1 failed");
        }
        int sak1 = select(SEL_CL1, ac1);

        // TODO 2: FOUR-byte UID case -- if ((sak1 & CASCADE_BIT) == 0):
        //         byte[] uid = { ac1[0], ac1[1], ac1[2], ac1[3] };
        //         return new ActivatedCard(uid, sak1, rats());
        if((sak1 & CASCADE_BIT) == 0){
            byte[] uid4 = {ac1[0], ac1[1], ac1[2], ac1[3]};
            return new ActivatedCard(uid4, sak1, rats());
        }

        // TODO 3: SEVEN-byte UID case (cascade bit set):
        //         byte[] ac2 = anticollision(SEL_CL2);
        //         int sak2 = select(SEL_CL2, ac2);
        //         Assemble the 7-byte uid: ac1[1],ac1[2],ac1[3], ac2[0..3].
        //         return new ActivatedCard(uid7, sak2, rats());

        byte[] ac2 = anticollision(SEL_CL2);
        if(ac2.length == 0){
            throw new SpiException("anticollision CL2 failed");
        }
        int sak2 = select(SEL_CL2, ac2);
        if((sak2 & CASCADE_BIT) == 0){
            byte[] uid7 = {ac1[1], ac1[2], ac1[3], ac2[0], ac2[1], ac2[2], ac2[3]};
            return new ActivatedCard(uid7, sak2, rats());
        }
        throw new SpiException("cannot find a valid UID: cascade bit set on CL2 SAK");
    }

    // ═════════════════════════════════════════════════════════════════
    // M6 — exchange(): send a payload as a CRC-protected T=CL frame and return
    // the reply's payload (with its 2 CRC bytes stripped).
    //
    // This is the same "append CRC, transceive" you already did inside select()
    // and rats(), pulled out as a reusable primitive. The ISO-DEP channel sits
    // on top of it: it hands us [PCB + apdu], we handle the CRC and the wire.
    // ═════════════════════════════════════════════════════════════════
    public byte[] exchange(byte[] payload) throws SpiException {
        // TODO 1: byte[] crc = calculateCrc(payload);
        byte[] crc = calculateCrc(payload);
        // TODO 2: byte[] frame = new byte[payload.length + 2];
        //         arraycopy payload in; frame[payload.length] = crc[0];
        //         frame[payload.length + 1] = crc[1];
        byte[] frame = new byte[payload.length + 2];
        System.arraycopy(payload, 0, frame, 0, payload.length);
        frame[payload.length] = crc[0];
        frame[payload.length + 1] = crc[1];
        // TODO 3: byte[] reply = transceive(frame, 0);
        byte[] reply = transceive(frame,0);
        // TODO 4: if (reply.length < 2) return new byte[0];
        //         return java.util.Arrays.copyOf(reply, reply.length - 2);  // drop reply CRC
        if(reply.length < 2) {
            return new byte[0];
        }
        return java.util.Arrays.copyOf(reply, reply.length - 2);
    }
}
