package com.midnightbrewer.hardware.crypto;

import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DesfireSessionTest {
    // derive from the test-vector inputs, assert the two keys match
    @Test
    void deriveSessionKey() {
        /*
        authKey       = 00112233445566778899aabbccddeeff
        RndA          = a0a1a2a3a4a5a6a7a8a9aaabacadaeaf
        RndB          = b0b1b2b3b4b5b6b7b8b9babbbcbdbebf
        → sessionKeyEnc = d46c03528ecd3b1f1148e708d3e9729d
        → sessionKeyMac = 116d156cc502da820eb57915f189ec74
         */

        byte[] authKey = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        byte[] rndA = HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
        byte[] rndB = HexFormat.of().parseHex("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf");

        DesfireSession session = new DesfireSession(authKey, rndA, rndB);
        byte[] sessionKeyEnc = session.encKey();
        byte[] sessionKeyMac = session.macKey();

        assertEquals("d46c03528ecd3b1f1148e708d3e9729d", HexFormat.of().formatHex(sessionKeyEnc));
        assertEquals("116d156cc502da820eb57915f189ec74", HexFormat.of().formatHex(sessionKeyMac));
    }
}
