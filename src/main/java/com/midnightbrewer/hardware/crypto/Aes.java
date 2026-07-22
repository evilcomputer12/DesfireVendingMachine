package com.midnightbrewer.hardware.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128 primitives, using Java's built-in crypto (JCE).
 *
 * We do NOT reimplement AES itself -- it's a solved problem and the JDK does it
 * correctly and fast. You build the DESFire-specific things (CMAC, session
 * keys, IVs) ON TOP of these three calls. This is the same split you'd make in
 * C: use a trusted AES library, write the protocol crypto around it.
 */
public final class Aes {

    public static final int BLOCK = 16; // AES block size in bytes

    private Aes() {
    }

    /** Encrypt one 16-byte block with AES-128 in ECB, no padding. */
    public static byte[] ecbEncrypt(byte[] key, byte[] block) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(block);
        } catch (Exception e) {
            throw new IllegalStateException("AES-ECB encrypt failed", e);
        }
    }

    /** CBC encrypt (no padding). {@code data} length must be a multiple of 16. */
    public static byte[] cbcEncrypt(byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-CBC encrypt failed", e);
        }
    }

    /** CBC decrypt (no padding). {@code data} length must be a multiple of 16. */
    public static byte[] cbcDecrypt(byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-CBC decrypt failed", e);
        }
    }

    /** XOR two equal-length byte arrays (handy for CMAC and CBC-by-hand). */
    public static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }
}
