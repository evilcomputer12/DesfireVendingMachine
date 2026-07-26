package com.midnightbrewer.hardware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HexFormat;
import com.midnightbrewer.hardware.crypto.DesfireSession;

public class DesfireSessionTest {
    /*
    authKey = 00112233445566778899aabbccddeeff
    rndA    = a0a1a2a3a4a5a6a7a8a9aaabacadaeaf
    rndB    = b0b1b2b3b4b5b6b7b8b9babbbcbdbebf
    TI      = 11223344            → new DesfireSession(authKey, rndA, rndB, TI)

     */

    private static byte[] h(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    public void deriveSessionKey() {
        byte[] authKey = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        byte[] rndA = HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
        byte[] rndB = HexFormat.of().parseHex("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
        byte[] ti = HexFormat.of().parseHex("11223344");
        DesfireSession s = new DesfireSession(authKey, rndA, rndB, h("11223344"));
        assertEquals("4d131ee17b482b16", hex(s.commandMac((byte) 0x6C, h("01")))); // ctr 0
        s.incrementCounter();                                                       // ctr -> 1
        assertEquals("6b082e8062d1a89d", hex(s.responseMac(new byte[0])));          // ctr 1
    }
    @Test
    public void encryptDecryptCommandData() {
        byte[] authKey = h("00112233445566778899aabbccddeeff");
        byte[] rndA = h("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
        byte[] rndB = h("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
        DesfireSession s = new DesfireSession(authKey, rndA, rndB, h("11223344"));
        assertEquals("dd42134cd6970980d22066cb00ab1b16", hex(s.commandIv()));                     // ctr 0
        assertEquals("a2eb8f000bf3ddfe6e18e8859b284591", hex(s.encryptCommandData(h("96000000"))));
        s.incrementCounter();                                                                      // ctr -> 1
        assertEquals("cb73ae774aafd81868062906135969b9", hex(s.responseIv()));
        byte[] plain = s.decryptResponseData(h("7950692fc2f01e72b3cee290f7122b5c"));
        // assert plain[0..3] little-endian == 1000  (e8 03 00 00)
    }
}
