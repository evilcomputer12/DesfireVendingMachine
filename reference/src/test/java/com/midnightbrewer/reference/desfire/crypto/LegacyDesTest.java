package com.midnightbrewer.reference.desfire.crypto;

import com.midnightbrewer.reference.util.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DES / 2K3DES / 3K3DES pinned against the compiled {@code desfire_crypto.c}
 * legacy primitives ({@code df_3des_*}, {@code df_3k3des_*}, {@code df_des_*}).
 *
 * <p>The two vectors that matter most are the all-zero-key ones: that is the
 * factory PICC master key, and the reason the demo can reach a blank card at
 * all. The last two tests demonstrate the weak-key equivalence the brief calls
 * out -- for the zero key the D40 "send" (decrypt) and the C's CBC-encrypt
 * construction are byte-identical, and for a non-zero key they diverge.
 */
class LegacyDesTest {

    private static final byte[] ZERO_IV8 = new byte[8];
    private static final byte[] DATA16 = ramp(0x30, 16);

    @Test
    void tripleDesCbcEncryptZeroKeyMatchesC() {
        assertArrayEquals(Hex.decode("A068DBEAB73D140B32DB64507D95A6F9"),
                LegacyDes.tripleDesCbcEncrypt(new byte[16], ZERO_IV8, DATA16));
    }

    @Test
    void tripleDesCbcDecryptZeroKeyMatchesC() {
        assertArrayEquals(Hex.decode("A068DBEAB73D140B664B469EA86F6961"),
                LegacyDes.tripleDesCbcDecrypt(new byte[16], ZERO_IV8, DATA16));
    }

    @Test
    void tripleDesEcbDecryptZeroKeyMatchesC() {
        assertArrayEquals(Hex.decode("A068DBEAB73D140B567A74AD9C5A5F56"),
                LegacyDes.tripleDesEcbDecrypt(new byte[16], DATA16));
    }

    @Test
    void tripleDesCbcEncryptNonZeroKeyMatchesC() {
        assertArrayEquals(Hex.decode("403C5F3041621C8E9A3A2861560DEC5F"),
                LegacyDes.tripleDesCbcEncrypt(ramp(0x01, 16), ZERO_IV8, DATA16));
    }

    @Test
    void tripleDesEcbDecryptNonZeroKeyMatchesC() {
        assertArrayEquals(Hex.decode("B9DB73CF72847305A2C6539B00978795"),
                LegacyDes.tripleDesEcbDecrypt(ramp(0x01, 16), DATA16));
    }

    @Test
    void tripleDes3kCbcEncryptMatchesC() {
        assertArrayEquals(Hex.decode("B1DDF50A4C835DAE730EAB2CDAB5E94E"),
                LegacyDes.tripleDes3kCbcEncrypt(ramp(0x01, 24), ZERO_IV8, DATA16));
    }

    @Test
    void singleDesCbcEncryptMatchesC() {
        assertArrayEquals(Hex.decode("1B18B97A85F967E9DE1E6DDE0CEC9A77"),
                LegacyDes.desCbcEncrypt(ramp(0x01, 8), ZERO_IV8, DATA16));
    }

    @Test
    void d40SendZeroKeyEqualsCbcEncryptZeroKey() {
        // Weak-key equivalence: for the 16-zero-byte factory key, E_K == D_K,
        // so the D40 decrypt-send and the C's encrypt construction agree.
        byte[] d40 = LegacyDes.d40CbcSend(new byte[16], ZERO_IV8, DATA16);
        byte[] cbcEncrypt = LegacyDes.tripleDesCbcEncrypt(new byte[16], ZERO_IV8, DATA16);
        assertTrue(DesfireCrypto.constantTimeEquals(d40, cbcEncrypt));
        assertArrayEquals(Hex.decode("A068DBEAB73D140B32DB64507D95A6F9"), d40);
    }

    @Test
    void d40SendNonZeroKeyDivergesFromCbcEncrypt() {
        byte[] key = ramp(0x01, 16);
        byte[] d40 = LegacyDes.d40CbcSend(key, ZERO_IV8, DATA16);
        byte[] cbcEncrypt = LegacyDes.tripleDesCbcEncrypt(key, ZERO_IV8, DATA16);
        assertFalse(DesfireCrypto.constantTimeEquals(d40, cbcEncrypt));
        // D40 send with the non-zero key, straight from the compiled C.
        assertArrayEquals(Hex.decode("B9DB73CF7284730539685FA48988640F"), d40);
    }

    private static byte[] ramp(int start, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (start + i);
        }
        return out;
    }
}
