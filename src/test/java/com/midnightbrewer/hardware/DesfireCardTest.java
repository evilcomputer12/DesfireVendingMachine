package com.midnightbrewer.hardware;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves AuthenticateEV2First against the replay vectors -- no reader, no card.
 *
 * The fake channel plays the card's two encrypted replies; a fixed RandomSource
 * pins RndA so the handshake is deterministic. If the whole thing runs without
 * throwing (the RndA' check passed) and TI comes out right, the handshake is
 * provably correct before it ever touches silicon.
 */
class DesfireCardTest {

    private static final HexFormat HEX = HexFormat.of();

    private static byte[] h(String s) {
        return HEX.parseHex(s);
    }

    private static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    @Test
    void authenticateEv2First_matchesReplayVectors() throws SpiException {
        byte[] key = h("00112233445566778899aabbccddeeff");
        byte[] rndA = h("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");

        FakeApduChannel channel = new FakeApduChannel();
        // step-1 reply: encRndB + status 91 AF
        channel.queueReply(h("eab6822fe368d5bc9895fb2558b38dde" + "91af"));
        // step-2 reply: encResp (TI ‖ rotL(RndA) ‖ caps) + status 91 00
        channel.queueReply(h("4e08e447d74810052caa1c5b6c60343f"
                + "51f6e901ad171e677b100d3a34c925e4" + "9100"));

        RandomSource fixedRndA = n -> rndA; // pin RndA -> deterministic handshake

        DesfireCard card = new DesfireCard(channel, fixedRndA);
        card.authenticateEv2First((byte) 2, key); // no throw == RndA' verified

        // the card's TI came out of the decrypted step-2 reply
        assertEquals("11223344", hex(card.getTi()));

        // and what we SENT in step 2 was the right ciphertext.
        // step-2 APDU = 90 AF 00 00 20 <encAB(32)> 00, so encAB is bytes [5..37].
        byte[] step2 = channel.sent.get(1);
        assertEquals("cf086a82c0b745a749daabb28a7a8db3"
                + "32272f9e5fdc37a4746064f25b7c2ca7",
                hex(Arrays.copyOfRange(step2, 5, 37)));
    }
}
