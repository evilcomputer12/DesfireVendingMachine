package com.midnightbrewer.reference.desfire.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * DES, 2K3DES and 3K3DES for the two pre-AES DESFire authentications.
 *
 * <p>These exist for one reason: a factory DESFire's PICC master key is sixteen
 * zero bytes of type <em>2K3DES</em>, not AES. {@code AuthenticateEV2First}
 * cannot reach it, so provisioning a blank card has to start with
 * {@code Authenticate} (0x0A) or {@code AuthenticateISO} (0x1A). Once the
 * application exists and its keys are AES, none of this is used again.
 *
 * <p>Ports {@code df_3des_cbc_encrypt}, {@code df_3des_cbc_decrypt},
 * {@code df_3des_ecb_decrypt}, {@code df_3k3des_cbc_encrypt},
 * {@code df_3k3des_cbc_decrypt}, {@code df_des_cbc_encrypt} and
 * {@code df_des_cbc_decrypt}. The C carries its own DES implementation; the JDK
 * has {@code DESede}, and {@code LegacyDesTest} pins every method here against
 * output from the compiled C, including for the all-zero key.
 *
 * <h2>Everything is DESede</h2>
 *
 * <p>Three key lengths, one engine. A 16-byte 2K3DES key is expanded to
 * {@code K1 | K2 | K1} and an 8-byte single-DES key to {@code K | K | K},
 * exactly as the C does, because {@code E_K1(D_K1(E_K1(x))) == E_K1(x)} makes
 * three-key DES with a repeated key <em>identical</em> to single DES. That
 * avoids a second cipher and a second set of vectors.
 *
 * <p>Note that the JDK will happily accept the all-zero key through
 * {@link SecretKeySpec}: the weak-key rejection lives in
 * {@code SecretKeyFactory}, which is deliberately not used here. Refusing the
 * factory key would make blank cards unreachable.
 */
public final class LegacyDes {

    /** DES block size in bytes. Everything in a legacy handshake is a multiple of this. */
    public static final int BLOCK_SIZE = 8;

    private static final String ALGORITHM = "DESede";
    private static final String CBC = "DESede/CBC/NoPadding";
    private static final String ECB = "DESede/ECB/NoPadding";

    private LegacyDes() {
    }

    // ----------------------------------------------------------- key expansion

    /** Expands a 16-byte 2K3DES key into the {@code K1 | K2 | K1} form DESede wants. */
    public static byte[] expand2k3des(byte[] key16) {
        require(key16, 16, "2K3DES key");
        byte[] out = new byte[24];
        System.arraycopy(key16, 0, out, 0, 16);
        System.arraycopy(key16, 0, out, 16, 8);
        return out;
    }

    /** Expands an 8-byte single-DES key into the {@code K | K | K} form. */
    public static byte[] expandDes(byte[] key8) {
        require(key8, 8, "DES key");
        byte[] out = new byte[24];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(key8, 0, out, i * 8, 8);
        }
        return out;
    }

    // ------------------------------------------------------------------ 2K3DES

    /** 2K3DES CBC encryption. Port of {@code df_3des_cbc_encrypt}. */
    public static byte[] tripleDesCbcEncrypt(byte[] key16, byte[] iv, byte[] data) {
        return cbc(expand2k3des(key16), iv, data, Cipher.ENCRYPT_MODE);
    }

    /** 2K3DES CBC decryption. Port of {@code df_3des_cbc_decrypt}. */
    public static byte[] tripleDesCbcDecrypt(byte[] key16, byte[] iv, byte[] data) {
        return cbc(expand2k3des(key16), iv, data, Cipher.DECRYPT_MODE);
    }

    /** 2K3DES ECB decryption -- the raw block primitive. Port of {@code df_3des_ecb_decrypt}. */
    public static byte[] tripleDesEcbDecrypt(byte[] key16, byte[] data) {
        return ecb(expand2k3des(key16), data, Cipher.DECRYPT_MODE);
    }

    /**
     * 2K3DES ECB encryption.
     *
     * <p>Not exported by the C, which only ever needs the decrypt direction.
     * It is here so tests can model the card side of a legacy handshake.
     */
    public static byte[] tripleDesEcbEncrypt(byte[] key16, byte[] data) {
        return ecb(expand2k3des(key16), data, Cipher.ENCRYPT_MODE);
    }

    // ------------------------------------------------------------------ 3K3DES

    /** 3K3DES CBC encryption with a 24-byte key. Port of {@code df_3k3des_cbc_encrypt}. */
    public static byte[] tripleDes3kCbcEncrypt(byte[] key24, byte[] iv, byte[] data) {
        require(key24, 24, "3K3DES key");
        return cbc(key24, iv, data, Cipher.ENCRYPT_MODE);
    }

    /** 3K3DES CBC decryption with a 24-byte key. Port of {@code df_3k3des_cbc_decrypt}. */
    public static byte[] tripleDes3kCbcDecrypt(byte[] key24, byte[] iv, byte[] data) {
        require(key24, 24, "3K3DES key");
        return cbc(key24, iv, data, Cipher.DECRYPT_MODE);
    }

    /** Builds the 24-byte 3K3DES key the C derives from a 16-byte key: {@code K | K[0..8]}. */
    public static byte[] to3k3desKey(byte[] key16) {
        return expand2k3des(key16);
    }

    // --------------------------------------------------------------- single DES

    /** Single-DES CBC encryption. Port of {@code df_des_cbc_encrypt}. */
    public static byte[] desCbcEncrypt(byte[] key8, byte[] iv, byte[] data) {
        return cbc(expandDes(key8), iv, data, Cipher.ENCRYPT_MODE);
    }

    /** Single-DES CBC decryption. Port of {@code df_des_cbc_decrypt}. */
    public static byte[] desCbcDecrypt(byte[] key8, byte[] iv, byte[] data) {
        return cbc(expandDes(key8), iv, data, Cipher.DECRYPT_MODE);
    }

    // ------------------------------------------------------------- D40 chaining

    /**
     * The native DESFire (D40) "CBC send" transform:
     * {@code c[i] = DEC(p[i] XOR c[i-1])}, with {@code c[-1]} the IV.
     *
     * <p>The structural oddity of D40 is that the <em>reader</em> applies the
     * decrypt primitive when sending. That is what the specification says, and
     * it is what this implements.
     *
     * <p>{@code df_authenticate_legacy} instead builds its step-2 token with
     * {@code df_3des_cbc_encrypt}, the ordinary encrypt direction. The two
     * disagree in general -- and {@code LegacyDesTest} demonstrates that they
     * do, for a non-zero key -- but they are byte-for-byte identical for the
     * only key ever presented at PICC level here. Sixteen zero bytes is a DES
     * weak key, every round subkey is the same, and so {@code E_K == D_K}.
     * Compiling the C and comparing both constructions confirms it: identical
     * for the zero key, different for {@code 01..10}.
     *
     * <p>{@link #tripleDesCbcEncrypt} is kept alongside so a card that wants
     * the other construction can still be reached; see
     * {@code DesfireCard.authenticateLegacy}.
     */
    public static byte[] d40CbcSend(byte[] key16, byte[] iv, byte[] data) {
        require(iv, BLOCK_SIZE, "IV");
        requireAligned(data);
        byte[] out = new byte[data.length];
        byte[] chain = iv.clone();
        for (int offset = 0; offset < data.length; offset += BLOCK_SIZE) {
            byte[] block = new byte[BLOCK_SIZE];
            for (int i = 0; i < BLOCK_SIZE; i++) {
                block[i] = (byte) (data[offset + i] ^ chain[i]);
            }
            byte[] enciphered = tripleDesEcbDecrypt(key16, block);
            System.arraycopy(enciphered, 0, out, offset, BLOCK_SIZE);
            chain = enciphered;
        }
        return out;
    }

    /**
     * The D40 "CBC receive" transform: {@code p[i] = DEC(c[i]) XOR c[i-1]}.
     *
     * <p>That is ordinary CBC decryption, so this delegates. The alias exists
     * so both halves of the handshake read symmetrically at the call site.
     */
    public static byte[] d40CbcReceive(byte[] key16, byte[] iv, byte[] data) {
        return tripleDesCbcDecrypt(key16, iv, data);
    }

    // ------------------------------------------------------------------ plumbing

    private static byte[] cbc(byte[] key24, byte[] iv, byte[] data, int mode) {
        require(iv, BLOCK_SIZE, "IV");
        requireAligned(data);
        try {
            Cipher cipher = Cipher.getInstance(CBC);
            cipher.init(mode, new SecretKeySpec(key24, ALGORITHM), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("DESede/CBC is unavailable", e);
        }
    }

    private static byte[] ecb(byte[] key24, byte[] data, int mode) {
        requireAligned(data);
        try {
            Cipher cipher = Cipher.getInstance(ECB);
            cipher.init(mode, new SecretKeySpec(key24, ALGORITHM));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("DESede/ECB is unavailable", e);
        }
    }

    private static void requireAligned(byte[] data) {
        if (data == null || data.length == 0 || data.length % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException(
                    "DES payload must be a non-empty multiple of " + BLOCK_SIZE
                            + " bytes, got " + (data == null ? "null" : data.length));
        }
    }

    private static void require(byte[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(
                    name + " must be " + length + " bytes, got "
                            + (value == null ? "null" : value.length));
        }
    }
}
