package com.midnightbrewer.reference.desfire.crypto;

import com.midnightbrewer.reference.util.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AES-CMAC against the published RFC 4493 examples.
 *
 * <p>These are the canonical vectors, so a pass here proves the implementation
 * is standard CMAC. Compiling {@code desfire_crypto.c} and running {@code df_cmac}
 * over the same inputs reproduces these exact values -- confirming the C library
 * this module ports is itself standard CMAC and not a variant, and that the two
 * therefore agree on every session key and every command MAC.
 */
class AesCmacTest {

    /** The RFC 4493 example key K. */
    private static final byte[] KEY = Hex.decode("2b7e151628aed2a6abf7158809cf4f3c");

    /** The RFC 4493 example message M (64 bytes), used in prefixes. */
    private static final byte[] MESSAGE = Hex.decode(
            "6bc1bee22e409f96e93d7e117393172a"
                    + "ae2d8a571e03ac9c9eb76fac45af8e51"
                    + "30c81c46a35ce411e5fbc1191a0a52ef"
                    + "f69f2445df4f9b17ad2b417be66c3710");

    @Test
    void rfc4493EmptyMessage() {
        assertEquals("BB1D6929E95937287FA37D129B756746",
                Hex.encode(AesCmac.calculate(KEY, new byte[0])).replace(" ", ""));
    }

    @Test
    void rfc4493SixteenByteMessage() {
        byte[] message = prefix(16);
        assertEquals("070A16B46B4D4144F79BDD9DD04A287C",
                Hex.encode(AesCmac.calculate(KEY, message)).replace(" ", ""));
    }

    @Test
    void rfc4493FortyByteMessage() {
        byte[] message = prefix(40);
        assertEquals("DFA66747DE9AE63030CA32611497C827",
                Hex.encode(AesCmac.calculate(KEY, message)).replace(" ", ""));
    }

    @Test
    void rfc4493SixtyFourByteMessage() {
        byte[] message = prefix(64);
        assertEquals("51F0BEBF7E3B9D92FC49741779363CFE",
                Hex.encode(AesCmac.calculate(KEY, message)).replace(" ", ""));
    }

    @Test
    void truncationTakesTheOddIndexedBytes() {
        // Matches df_truncate_mac on 0xF0..0xFF: bytes 1,3,5,...,15.
        byte[] mac = Hex.decode("F0F1F2F3F4F5F6F7F8F9FAFBFCFDFEFF");
        assertArrayEquals(Hex.decode("F1F3F5F7F9FBFDFF"), AesCmac.truncate(mac));
    }

    @Test
    void truncationRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> AesCmac.truncate(new byte[8]));
    }

    private static byte[] prefix(int length) {
        byte[] out = new byte[length];
        System.arraycopy(MESSAGE, 0, out, 0, length);
        return out;
    }
}
