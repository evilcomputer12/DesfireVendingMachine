package com.midnightbrewer.reference.support;

import com.midnightbrewer.reference.pcd.crc.SoftwareCrcCalculator;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A card that behaves like a DESFire: 7-byte UID, two cascade levels, RATS, and
 * ISO 14443-4 with chaining and optional waiting-time extensions.
 *
 * <p>Complete enough that the driver has to get the protocol right. It checks
 * the CRC on every ISO-DEP frame it receives, enforces that a chained block is
 * followed by another block rather than a new APDU, and answers with the block
 * number it was sent -- so a broken toggle, a missing CRC or a mis-sized chunk
 * all show up as a test failure rather than as silence.
 *
 * <p>It records every reassembled APDU, which is what lets a chaining test
 * assert that a 100-byte command arrived as one piece after being split into
 * three frames.
 */
public final class SimulatedDesfireCard implements SimulatedPicc {

    /** ATQA for a DESFire: the C's comment lists {@code 0x4403}. */
    private static final byte[] ATQA = {0x44, 0x03};

    /** SAK at cascade level 1: bit 0x04 set means "UID incomplete". */
    private static final byte SAK_CASCADE = 0x04;

    /** SAK at cascade level 2: bit 0x20 means ISO 14443-4 supported. */
    private static final byte SAK_COMPLETE = 0x20;

    /** A typical DESFire EV1 ATS: TL T0 TA1 TB1 TC1 T1. */
    private static final byte[] ATS = {0x06, 0x75, 0x77, (byte) 0x81, 0x02, (byte) 0x80};

    private final SoftwareCrcCalculator crc = new SoftwareCrcCalculator();
    private final byte[] uid;
    private final List<byte[]> receivedApdus = new ArrayList<>();

    private final ByteArrayOutputStream inboundChain = new ByteArrayOutputStream();
    private ApduHandler handler = apdu -> new byte[] {(byte) 0x91, 0x00};

    /** Number of S(WTX) frames to send before answering the next APDU. */
    private int pendingWtxCount;
    private int wtxMultiplier = 1;

    /** True once RATS has been answered. */
    private boolean activated;

    public SimulatedDesfireCard() {
        this(new byte[] {0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
    }

    public SimulatedDesfireCard(byte[] uid) {
        if (uid.length != 7) {
            throw new IllegalArgumentException("this card models a 7-byte UID");
        }
        this.uid = uid.clone();
    }

    /** Decides what the card replies to a fully reassembled APDU. */
    @FunctionalInterface
    public interface ApduHandler {
        byte[] respondTo(byte[] apdu);
    }

    /** Installs the APDU behaviour. */
    public SimulatedDesfireCard respondingWith(ApduHandler handler) {
        this.handler = handler;
        return this;
    }

    /** Makes the card ask for {@code count} waiting-time extensions of {@code multiplier}. */
    public SimulatedDesfireCard requestingWtx(int count, int multiplier) {
        this.pendingWtxCount = count;
        this.wtxMultiplier = multiplier;
        return this;
    }

    /** Every APDU the card has reassembled, in order. */
    public List<byte[]> receivedApdus() {
        return List.copyOf(receivedApdus);
    }

    /** True once RATS has completed. */
    public boolean isActivated() {
        return activated;
    }

    @Override
    public byte[] exchange(byte[] request, int txLastBits) {
        if (request.length == 0) {
            return null;
        }
        int first = request[0] & 0xFF;

        // ---- ISO 14443-3 ----
        if (txLastBits == 7 && request.length == 1) {
            return (first == 0x26 || first == 0x52) ? ATQA.clone() : null;
        }
        if (first == 0x93 || first == 0x95) {
            return handleCascade(request, first);
        }
        if (first == 0xE0) {
            return handleRats(request);
        }
        if (first == 0x50) {
            return null;   // HALT: a card that answers has not halted
        }

        // ---- ISO 14443-4 ----
        return handleBlock(request);
    }

    private byte[] handleCascade(byte[] request, int sel) {
        if (request.length < 2) {
            return null;
        }
        int nvb = request[1] & 0xFF;

        if (nvb == 0x20) {
            byte[] payload = sel == 0x93
                    ? new byte[] {(byte) 0x88, uid[0], uid[1], uid[2]}
                    : new byte[] {uid[3], uid[4], uid[5], uid[6]};
            byte[] reply = Arrays.copyOf(payload, 5);
            int bcc = 0;
            for (byte b : payload) {
                bcc ^= b & 0xFF;
            }
            reply[4] = (byte) bcc;
            return reply;
        }

        if (nvb == 0x70) {
            if (!hasValidCrc(request, request.length)) {
                return null;
            }
            byte sak = sel == 0x93 ? SAK_CASCADE : SAK_COMPLETE;
            return withCrc(new byte[] {sak});
        }
        return null;
    }

    private byte[] handleRats(byte[] request) {
        if (request.length < 4 || !hasValidCrc(request, request.length)) {
            return null;
        }
        activated = true;
        inboundChain.reset();
        return ATS.clone();
    }

    private byte[] handleBlock(byte[] request) {
        if (!activated || request.length < 3 || !hasValidCrc(request, request.length)) {
            return null;
        }
        int pcb = request[0] & 0xFF;
        int blockType = pcb & 0xC0;

        // S-block: the reader answering our own S(WTX).
        if (blockType == 0xC0) {
            if ((pcb & 0x30) == 0x30) {
                return continueOrRespond();
            }
            return withCrc(new byte[] {(byte) 0xC2});   // S(DESELECT) reply
        }

        // I-block.
        if (blockType != 0x00) {
            return null;
        }
        int blockNumber = pcb & 0x01;
        boolean chaining = (pcb & 0x10) != 0;

        byte[] inf = Arrays.copyOfRange(request, 1, request.length - 2);
        inboundChain.writeBytes(inf);

        if (chaining) {
            // R(ACK) carrying the block number just received.
            return withCrc(new byte[] {(byte) (0xA2 | blockNumber)});
        }

        byte[] apdu = inboundChain.toByteArray();
        inboundChain.reset();
        receivedApdus.add(apdu);
        pendingResponse = handler.respondTo(apdu);
        pendingBlockNumber = blockNumber;

        return continueOrRespond();
    }

    private byte[] pendingResponse;
    private int pendingBlockNumber;

    /** Emits an S(WTX) if any remain, otherwise the queued I-block response. */
    private byte[] continueOrRespond() {
        if (pendingWtxCount > 0) {
            pendingWtxCount--;
            return withCrc(new byte[] {(byte) 0xF2, (byte) wtxMultiplier});
        }
        byte[] body = pendingResponse == null ? new byte[0] : pendingResponse;
        byte[] frame = new byte[1 + body.length];
        frame[0] = (byte) (0x02 | pendingBlockNumber);
        System.arraycopy(body, 0, frame, 1, body.length);
        return withCrc(frame);
    }

    private byte[] withCrc(byte[] body) {
        byte[] frame = Arrays.copyOf(body, body.length + 2);
        byte[] check = crc.calculate(body, 0, body.length);
        frame[body.length] = check[0];
        frame[body.length + 1] = check[1];
        return frame;
    }

    /**
     * Checks the trailing CRC_A of a received frame.
     *
     * <p>Written out rather than calling {@code CrcCalculator.isValidFrame},
     * whose signature allows a hardware failure this simulator cannot have.
     */
    private boolean hasValidCrc(byte[] frame, int length) {
        if (length < 3) {
            return false;
        }
        byte[] expected = crc.calculate(frame, 0, length - 2);
        return frame[length - 2] == expected[0] && frame[length - 1] == expected[1];
    }
}
