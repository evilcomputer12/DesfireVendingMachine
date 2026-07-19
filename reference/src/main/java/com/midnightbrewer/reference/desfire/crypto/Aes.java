package com.midnightbrewer.reference.desfire.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * The three AES-128 primitives DESFire EV2 secure messaging needs.
 *
 * <p>Ports {@code df_aes_ecb_encrypt}, {@code df_aes_ecb_decrypt},
 * {@code df_aes_cbc_encrypt} and {@code df_aes_cbc_decrypt} from
 * {@code desfire_crypto.c}. The C ships its own AES because an ESP32 firmware
 * cannot assume a crypto library; the JDK has one, so this is a thin adapter
 * rather than a second implementation. {@code AesTest} pins every method
 * against vectors produced by compiling that C file.
 *
 * <p>Nothing here pads. DESFire pads with ISO/IEC 7816-4 ({@code 0x80} then
 * zeros), which is not a mode {@code javax.crypto} offers, so the transform is
 * always {@code NoPadding} and callers pad through
 * {@link DesfireCrypto#padIso7816}. Passing unaligned data is a programming
 * error and throws.
 */
public final class Aes {

    /** AES block size in bytes. */
    public static final int BLOCK_SIZE = 16;

    /** AES-128 key size in bytes. The only size DESFire AES keys come in. */
    public static final int KEY_SIZE = 16;

    private static final String ALGORITHM = "AES";
    private static final String ECB = "AES/ECB/NoPadding";
    private static final String CBC = "AES/CBC/NoPadding";

    private Aes() {
    }

    /**
     * Encrypts one 16-byte block with AES-128 in ECB.
     *
     * <p>The raw block cipher. It is the building block of AES-CMAC and of the
     * EV2 IV construction, both of which encrypt exactly one block, so a
     * single-block signature is the honest one -- a general ECB helper would
     * invite using ECB on real data, which is never right here.
     */
    public static byte[] ecbEncryptBlock(byte[] key, byte[] block) {
        requireKey(key);
        if (block.length != BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "AES block must be " + BLOCK_SIZE + " bytes, got " + block.length);
        }
        return run(ECB, Cipher.ENCRYPT_MODE, key, null, block);
    }

    /** Decrypts one 16-byte block with AES-128 in ECB. */
    public static byte[] ecbDecryptBlock(byte[] key, byte[] block) {
        requireKey(key);
        if (block.length != BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "AES block must be " + BLOCK_SIZE + " bytes, got " + block.length);
        }
        return run(ECB, Cipher.DECRYPT_MODE, key, null, block);
    }

    /** AES-128-CBC encryption. {@code data} must be block aligned. */
    public static byte[] cbcEncrypt(byte[] key, byte[] iv, byte[] data) {
        requireKey(key);
        requireIv(iv);
        requireAligned(data);
        return run(CBC, Cipher.ENCRYPT_MODE, key, iv, data);
    }

    /** AES-128-CBC decryption. {@code data} must be block aligned. */
    public static byte[] cbcDecrypt(byte[] key, byte[] iv, byte[] data) {
        requireKey(key);
        requireIv(iv);
        requireAligned(data);
        return run(CBC, Cipher.DECRYPT_MODE, key, iv, data);
    }

    private static byte[] run(String transform, int mode, byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance(transform);
            SecretKeySpec spec = new SecretKeySpec(key, ALGORITHM);
            if (iv == null) {
                cipher.init(mode, spec);
            } else {
                cipher.init(mode, spec, new IvParameterSpec(iv));
            }
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            // AES-128 with NoPadding and aligned input cannot fail on a
            // conformant JRE, so reaching here means the platform is broken
            // rather than the card -- not something a caller can handle.
            throw new IllegalStateException("AES is unavailable or misconfigured: " + transform, e);
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_SIZE) {
            throw new IllegalArgumentException(
                    "AES-128 key must be " + KEY_SIZE + " bytes, got "
                            + (key == null ? "null" : key.length));
        }
    }

    private static void requireIv(byte[] iv) {
        if (iv == null || iv.length != BLOCK_SIZE) {
            throw new IllegalArgumentException(
                    "AES IV must be " + BLOCK_SIZE + " bytes, got "
                            + (iv == null ? "null" : iv.length));
        }
    }

    private static void requireAligned(byte[] data) {
        if (data == null || data.length == 0 || data.length % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException(
                    "AES-CBC payload must be a non-empty multiple of " + BLOCK_SIZE
                            + " bytes, got " + (data == null ? "null" : data.length));
        }
    }
}
