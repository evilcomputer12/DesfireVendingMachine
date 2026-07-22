package com.midnightbrewer.hardware.crypto;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves AesCmac against the published RFC 4493 test vectors.
 *
 * These four are THE reference vectors for AES-CMAC -- if your implementation
 * matches them, it's correct, full stop. No hardware needed; run on your Mac.
 * (This is exactly why crypto is done test-first: you verify against known
 * answers before trusting it with anything real.)
 */
class AesCmacTest {

    private static final HexFormat HEX = HexFormat.of();

    /** The RFC 4493 example key. */
    private static final byte[] KEY = HEX.parseHex("2b7e151628aed2a6abf7158809cf4f3c");

    /** The 64-byte example message (examples use prefixes of it). */
    private static final String MSG =
            "6bc1bee22e409f96e93d7e117393172a"
          + "ae2d8a571e03ac9c9eb76fac45af8e51"
          + "30c81c46a35ce411e5fbc1191a0a52ef"
          + "f69f2445df4f9b17ad2b417be66c3710";

    private static byte[] cmac(String hexMessage) {
        return new AesCmac(KEY).compute(HEX.parseHex(hexMessage));
    }

    private static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    @Test
    void rfc4493_example1_emptyMessage() {
        assertEquals("bb1d6929e95937287fa37d129b756746", hex(cmac("")));
    }

    @Test
    void rfc4493_example2_16bytes() {
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c",
                hex(cmac(MSG.substring(0, 32))));   // first 16 bytes
    }

    @Test
    void rfc4493_example3_40bytes() {
        assertEquals("dfa66747de9ae63030ca32611497c827",
                hex(cmac(MSG.substring(0, 80))));   // first 40 bytes (incomplete last block)
    }

    @Test
    void rfc4493_example4_64bytes() {
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe",
                hex(cmac(MSG)));                     // all 64 bytes
    }
}
