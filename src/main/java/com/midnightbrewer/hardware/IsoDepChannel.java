package com.midnightbrewer.hardware;

import java.util.Arrays;

/**
 * M6 — the ISO-14443-4 (ISO-DEP) channel.
 *
 * After a card is activated (RATS done), you talk to it in "T=CL blocks". This
 * class sends one APDU at a time, wrapped in an I-block, and returns the
 * response APDU.
 *
 * The OOP on show here:
 *   - COMPOSITION: it HAS-A reader (not extends -- a channel isn't a reader).
 *   - ENCAPSULATED STATE: the block number toggles 0<->1 each exchange and
 *     nobody outside can touch it.
 *   - It USES your M4 hierarchy: an InformationBlock encodes the outgoing
 *     frame, and this is where those block classes finally earn their keep.
 *
 * Simplifications for now (we add them later if needed): one I-block per APDU
 * (no chaining of huge payloads), and we assume the card replies promptly
 * (no S-block "wait" handling). Fine for DESFire's small command frames.
 */
public class IsoDepChannel implements ApduChannel {

    /** I-block PCB base; the low bit carries the block number. */
    private static final int IBLOCK_PCB = 0x02;

    private final Iso14443Reader reader; // composition: HAS-A reader
    private int blockNumber = 0;         // 0 <-> 1, toggled after each exchange

    public IsoDepChannel(Iso14443Reader reader) {
        this.reader = reader;
    }

    /**
     * Send one APDU, get the response APDU back.
     *   1. wrap it in an I-block for the current block number
     *   2. send it via the reader (which adds CRC and transceives)
     *   3. toggle the block number for next time
     *   4. the reply is [PCB, ...response...] -- strip the PCB and return
     */
    public byte[] transceive(byte[] apdu) throws SpiException {
        // TODO 1: build and encode the outgoing I-block:
        //         InformationBlock out = new InformationBlock(IBLOCK_PCB | blockNumber);
        //         byte[] frame = out.encode(apdu);

        InformationBlock out = new InformationBlock(IBLOCK_PCB | blockNumber);
        byte[] frame = out.encode(apdu);
        // TODO 2: byte[] reply = reader.exchange(frame);
        byte[] reply = reader.exchange(frame);

        // TODO 3: blockNumber ^= 1;   // toggle for the next exchange
        blockNumber ^= 1;

        // TODO 4: if (reply.length < 1) throw new SpiException("empty ISO-DEP reply");
        //         return Arrays.copyOfRange(reply, 1, reply.length);   // drop the PCB
        if (reply.length < 1) {
            throw new SpiException("empty ISO-DEP reply");
        }
        return Arrays.copyOfRange(reply, 1, reply.length);   // drop the PCB
    }
}
