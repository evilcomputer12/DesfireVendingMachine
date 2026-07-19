package com.midnightbrewer.reference.desfire.crypto;

import com.midnightbrewer.reference.util.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AES ECB and CBC pinned against vectors produced by compiling
 * {@code desfire_crypto.c} ({@code df_aes_ecb_*}, {@code df_aes_cbc_*}) and
 * running it on the same inputs. This is what guarantees the JDK cipher used
 * here is byte-for-byte identical to the C the rest of the stack was validated
 * against.
 */
class AesTest {

    private static final byte[] KEY22 = filled(0x22);

    @Test
    void ecbEncryptMatchesC() {
        byte[] block = counting(16);
        assertArrayEquals(Hex.decode("B7B472078695948D1FD79342B7123169"),
                Aes.ecbEncryptBlock(KEY22, block));
    }

    @Test
    void ecbDecryptMatchesC() {
        byte[] block = counting(16);
        assertArrayEquals(Hex.decode("8310CF64CE639F039D3B4EFE572CB2E4"),
                Aes.ecbDecryptBlock(KEY22, block));
    }

    @Test
    void cbcEncryptMatchesC() {
        byte[] iv = ramp(0xA0, 16);
        byte[] plaintext = ramp(0x30, 32);
        assertArrayEquals(
                Hex.decode("A8CE459419E6332D2CC98F9A30CC6711610A3AD27EEC10E9A5E31F4C77BFEA86"),
                Aes.cbcEncrypt(KEY22, iv, plaintext));
    }

    @Test
    void cbcEncryptWithZeroIvMatchesC() {
        byte[] iv = new byte[16];
        byte[] plaintext = ramp(0x30, 32);
        assertArrayEquals(
                Hex.decode("38C87CA76D77135E1B8CCDC4EE3AFD29981C3D502CC3A465777154CB24E365ED"),
                Aes.cbcEncrypt(KEY22, iv, plaintext));
    }

    @Test
    void cbcRoundTrips() {
        byte[] iv = ramp(0xA0, 16);
        byte[] plaintext = ramp(0x30, 32);
        byte[] cipher = Aes.cbcEncrypt(KEY22, iv, plaintext);
        assertArrayEquals(plaintext, Aes.cbcDecrypt(KEY22, iv, cipher));
    }

    @Test
    void rejectsUnalignedCbcInput() {
        assertThrows(IllegalArgumentException.class,
                () -> Aes.cbcEncrypt(KEY22, new byte[16], new byte[17]));
    }

    @Test
    void rejectsWrongKeySize() {
        assertThrows(IllegalArgumentException.class,
                () -> Aes.ecbEncryptBlock(new byte[15], new byte[16]));
    }

    private static byte[] filled(int value) {
        byte[] out = new byte[16];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    private static byte[] counting(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) i;
        }
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
