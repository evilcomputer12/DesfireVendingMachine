package com.midnightbrewer.reference.desfire.crypto;

import com.midnightbrewer.reference.util.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session key derivation, IV construction, CRC-32, padding and rotation, each
 * pinned against vectors from the compiled {@code desfire_crypto.c}
 * ({@code df_derive_session_key}, {@code _calc_iv_cmd}/{@code _calc_iv_resp}
 * as inlined in {@code desfire_cmd.c}, {@code df_crc32}).
 */
class DesfireCryptoTest {

    private static final byte[] KEY22 = filled(0x22);
    private static final byte[] RND_A = ramp(0x10, 16);
    private static final byte[] RND_B = ramp(0xA0, 16);

    @Test
    void sessionEncryptionKeyMatchesC() {
        assertArrayEquals(Hex.decode("1130B90C1AAC6EF7528D9088D24D67D3"),
                DesfireCrypto.deriveSessionKey(KEY22, RND_A, RND_B, DesfireCrypto.LABEL_ENCRYPTION));
    }

    @Test
    void sessionMacKeyMatchesC() {
        assertArrayEquals(Hex.decode("569031DCA11D038E1B12E50092C9E030"),
                DesfireCrypto.deriveSessionKey(KEY22, RND_A, RND_B, DesfireCrypto.LABEL_MAC));
    }

    @Test
    void sessionKeyWithZeroAuthKeyMatchesC() {
        byte[] zero = new byte[16];
        assertArrayEquals(Hex.decode("673DCF5F9EC6DC781140AA625B084C29"),
                DesfireCrypto.deriveSessionKey(zero, RND_A, RND_B, DesfireCrypto.LABEL_ENCRYPTION));
        assertArrayEquals(Hex.decode("4A8840E55068D27A5A47FA19CDD4472A"),
                DesfireCrypto.deriveSessionKey(zero, RND_A, RND_B, DesfireCrypto.LABEL_MAC));
    }

    @Test
    void rejectsBadSessionKeyLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> DesfireCrypto.deriveSessionKey(KEY22, RND_A, RND_B, 0x99));
    }

    @Test
    void commandIvMatchesC() {
        byte[] sessEnc = ramp(0x50, 16);
        byte[] ti = Hex.decode("DEADBEEF");
        assertArrayEquals(Hex.decode("6CD1B50842809CCA2DBE1B88ED326810"),
                DesfireCrypto.commandIv(sessEnc, ti, 0x0102));
    }

    @Test
    void responseIvMatchesC() {
        byte[] sessEnc = ramp(0x50, 16);
        byte[] ti = Hex.decode("DEADBEEF");
        assertArrayEquals(Hex.decode("94FE8DCEF890DF850D1C42D201C8F942"),
                DesfireCrypto.responseIv(sessEnc, ti, 0x0102));
    }

    @Test
    void commandIvAtCounterZeroMatchesC() {
        byte[] sessEnc = ramp(0x50, 16);
        byte[] ti = Hex.decode("DEADBEEF");
        assertArrayEquals(Hex.decode("065129803C4B784DACAFE06AF75F07F4"),
                DesfireCrypto.commandIv(sessEnc, ti, 0));
    }

    @Test
    void crc32MatchesCForKnownStrings() {
        // df_crc32 omits the final complement; "123456789" -> 0x340BC6D9.
        assertEquals(0x340BC6D9, DesfireCrypto.crc32("123456789".getBytes()));
        assertEquals(0x9736C8A7, DesfireCrypto.crc32(filled(0x11)));
        assertEquals(0xC0D14AF1, DesfireCrypto.crc32(filled(0x22)));
        assertEquals(0xFFFFFFFF, DesfireCrypto.crc32(new byte[0]));
    }

    @Test
    void crc32ToBytesIsLittleEndian() {
        assertArrayEquals(Hex.decode("D9C60B34"), DesfireCrypto.crc32ToBytes(0x340BC6D9));
    }

    @Test
    void padAddsAWholeBlockToAlignedInput() {
        byte[] padded = DesfireCrypto.padIso7816(new byte[16]);
        assertEquals(32, padded.length);
        assertEquals((byte) 0x80, padded[16]);
    }

    @Test
    void padThenUnpadRoundTrips() {
        for (int length = 0; length < 40; length++) {
            byte[] input = new byte[length];
            for (int i = 0; i < length; i++) {
                input[i] = (byte) (i * 13 + 7);
            }
            assertArrayEquals(input, DesfireCrypto.unpadIso7816(DesfireCrypto.padIso7816(input)),
                    "round trip length " + length);
        }
    }

    @Test
    void rotateLeftMovesTheFirstByteToTheEnd() {
        assertArrayEquals(Hex.decode("11223300"), DesfireCrypto.rotateLeft(Hex.decode("00112233")));
    }

    @Test
    void littleEndianInt32RoundTrips() {
        assertArrayEquals(Hex.decode("C4090000"), DesfireCrypto.int32ToLittleEndian(2500));
        assertEquals(2500, DesfireCrypto.int32FromLittleEndian(Hex.decode("C4090000"), 0));
        assertEquals(-1, DesfireCrypto.int32FromLittleEndian(Hex.decode("FFFFFFFF"), 0));
    }

    @Test
    void constantTimeEquals() {
        assertTrue(DesfireCrypto.constantTimeEquals(Hex.decode("0102"), Hex.decode("0102")));
        assertFalse(DesfireCrypto.constantTimeEquals(Hex.decode("0102"), Hex.decode("0103")));
        assertFalse(DesfireCrypto.constantTimeEquals(Hex.decode("0102"), Hex.decode("010203")));
    }

    private static byte[] filled(int value) {
        byte[] out = new byte[16];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    private static byte[] ramp(int start, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (start + i);
        }
        return out;
    }
}
