package com.midnightbrewer.reference.desfire.crypto;

/**
 * The DESFire-specific constructions built on top of {@link Aes} and
 * {@link AesCmac}: session key derivation, IVs, padding, nonce rotation and the
 * CRC-32 variant {@code ChangeKey} uses.
 *
 * <p>Every method is a port of a named function in {@code desfire_crypto.c} or
 * of one of the static helpers in {@code desfire_cmd.c}, and each says which.
 * They are static and stateless on purpose -- the state they operate on lives
 * in {@link com.midnightbrewer.reference.desfire.DesfireSession}, and keeping
 * the arithmetic separate from the state is what makes it testable against
 * fixed vectors.
 */
public final class DesfireCrypto {

    /** Session key derivation label for the encryption key (SV1). */
    public static final int LABEL_ENCRYPTION = 0xA5;

    /** Session key derivation label for the MAC key (SV2). */
    public static final int LABEL_MAC = 0x5A;

    /** Length of the transaction identifier the card issues at authentication. */
    public static final int TI_LENGTH = 4;

    /**
     * The reflected CRC-32 polynomial, {@code 0xEDB88320}.
     *
     * <p>Same polynomial as zip and Ethernet, but see {@link #crc32} for the
     * difference that matters.
     */
    private static final int CRC32_POLYNOMIAL = 0xEDB88320;

    private DesfireCrypto() {
    }

    // ------------------------------------------------------------- session keys

    /**
     * Derives one AES-128 session key from the authentication nonces.
     *
     * <p>Port of {@code df_derive_session_key}. The 32-byte derivation input is
     * an NXP-defined interleaving of the two nonces:
     *
     * <pre>
     *   [0]      label            0xA5 for encryption, 0x5A for MAC
     *   [1]      counter-label    the other one of the pair
     *   [2..5]   00 01 00 80      fixed counter / length field
     *   [6..7]   rndA[0..1]
     *   [8..13]  rndA[2..7] XOR rndB[0..5]
     *   [14..23] rndB[6..15]
     *   [24..31] rndA[8..15]
     * </pre>
     *
     * <p>and the key is its AES-CMAC under the authentication key. The XOR band
     * in the middle is what makes both nonces contribute to every session key:
     * neither side can steer the result on its own.
     *
     * @param key   the 16-byte key the authentication used
     * @param rndA  the reader's 16-byte nonce
     * @param rndB  the card's 16-byte nonce
     * @param label {@link #LABEL_ENCRYPTION} or {@link #LABEL_MAC}
     */
    public static byte[] deriveSessionKey(byte[] key, byte[] rndA, byte[] rndB, int label) {
        require(key, Aes.KEY_SIZE, "key");
        require(rndA, Aes.BLOCK_SIZE, "rndA");
        require(rndB, Aes.BLOCK_SIZE, "rndB");
        if (label != LABEL_ENCRYPTION && label != LABEL_MAC) {
            throw new IllegalArgumentException(
                    "session key label must be 0xA5 or 0x5A, got " + label);
        }

        byte[] sv = new byte[32];
        sv[0] = (byte) label;
        sv[1] = (byte) (label == LABEL_ENCRYPTION ? LABEL_MAC : LABEL_ENCRYPTION);
        sv[2] = 0x00;
        sv[3] = 0x01;
        sv[4] = 0x00;
        sv[5] = (byte) 0x80;
        sv[6] = rndA[0];
        sv[7] = rndA[1];
        for (int i = 0; i < 6; i++) {
            sv[8 + i] = (byte) (rndA[2 + i] ^ rndB[i]);
        }
        System.arraycopy(rndB, 6, sv, 14, 10);
        System.arraycopy(rndA, 8, sv, 24, 8);
        return AesCmac.calculate(key, sv);
    }

    // ---------------------------------------------------------------------- IVs

    /**
     * The IV for encrypting command data.
     *
     * <p>Port of {@code _calc_iv_cmd}:
     * {@code AES-ECB(sessKeyEnc, [0xA5, 0x5A, TI(4), ctrLo, ctrHi, 00 x8])}.
     *
     * <p>Because the counter is an input, every command in a session encrypts
     * under a different IV without either side transmitting one.
     */
    public static byte[] commandIv(byte[] sessionKeyEnc, byte[] ti, int commandCounter) {
        return iv(sessionKeyEnc, ti, commandCounter, 0xA5, 0x5A);
    }

    /**
     * The IV for decrypting response data: the same construction with the two
     * leading bytes swapped. Port of {@code _calc_iv_resp}.
     */
    public static byte[] responseIv(byte[] sessionKeyEnc, byte[] ti, int commandCounter) {
        return iv(sessionKeyEnc, ti, commandCounter, 0x5A, 0xA5);
    }

    private static byte[] iv(byte[] sessionKeyEnc, byte[] ti, int counter, int b0, int b1) {
        require(sessionKeyEnc, Aes.KEY_SIZE, "sessionKeyEnc");
        require(ti, TI_LENGTH, "TI");
        byte[] input = new byte[Aes.BLOCK_SIZE];
        input[0] = (byte) b0;
        input[1] = (byte) b1;
        System.arraycopy(ti, 0, input, 2, TI_LENGTH);
        input[6] = (byte) (counter & 0xFF);
        input[7] = (byte) ((counter >>> 8) & 0xFF);
        return Aes.ecbEncryptBlock(sessionKeyEnc, input);
    }

    // ------------------------------------------------------------------ padding

    /**
     * ISO/IEC 7816-4 padding: append {@code 0x80}, then zeros to the next
     * 16-byte boundary.
     *
     * <p>Port of the {@code padded_len = ((len | 0x0F) + 1)} idiom repeated
     * through {@code desfire_cmd.c}. Note that already-aligned input grows by a
     * whole block -- the {@code 0x80} is unconditional, which is what makes the
     * padding unambiguously strippable.
     */
    public static byte[] padIso7816(byte[] data) {
        int length = data == null ? 0 : data.length;
        byte[] out = new byte[(length | 0x0F) + 1];
        if (length > 0) {
            System.arraycopy(data, 0, out, 0, length);
        }
        out[length] = (byte) 0x80;
        return out;
    }

    /**
     * Strips ISO/IEC 7816-4 padding, returning the payload before the trailing
     * {@code 0x80}.
     *
     * <p>Returns the input unchanged when no valid padding is found, rather
     * than throwing: a decrypted block whose padding is wrong means the session
     * keys are wrong, and that is diagnosed far more clearly by the CMAC check
     * that has already run than by an exception from a formatting helper.
     */
    public static byte[] unpadIso7816(byte[] data) {
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == (byte) 0x80) {
                byte[] out = new byte[i];
                System.arraycopy(data, 0, out, 0, i);
                return out;
            }
            if (data[i] != 0x00) {
                break;
            }
        }
        return data.clone();
    }

    // ------------------------------------------------------------------- nonces

    /**
     * Rotates left by one byte: {@code data[1..n-1] || data[0]}.
     *
     * <p>Both halves of every DESFire authentication use this. Proving you can
     * return the other side's nonce rotated -- rather than echoed -- is what
     * shows you decrypted it rather than replayed the ciphertext.
     */
    public static byte[] rotateLeft(byte[] data) {
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] out = new byte[data.length];
        System.arraycopy(data, 1, out, 0, data.length - 1);
        out[data.length - 1] = data[0];
        return out;
    }

    // -------------------------------------------------------------------- CRC32

    /**
     * CRC-32 as DESFire computes it, for the {@code ChangeKey} integrity field.
     *
     * <p>Port of {@code df_crc32}. Reflected CRC-32 with the usual polynomial
     * and {@code 0xFFFFFFFF} initial value, but <em>without</em> the final
     * complement that CRC-32/ISO-HDLC applies -- so the check value over
     * {@code "123456789"} is {@code 0x340BC6D9}, the ones' complement of the
     * familiar {@code 0xCBF43926}. Using {@link java.util.zip.CRC32} here would
     * therefore produce an inverted value and make every {@code ChangeKey} fail
     * with an integrity error, which is why this is written out.
     */
    public static int crc32(byte[] data) {
        int crc = 0xFFFFFFFF;
        for (byte b : data) {
            crc ^= b & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ CRC32_POLYNOMIAL : crc >>> 1;
            }
        }
        return crc;
    }

    /** Encodes a CRC-32 as four little-endian bytes, the order {@code df_change_key} writes. */
    public static byte[] crc32ToBytes(int crc) {
        return new byte[] {
                (byte) (crc & 0xFF),
                (byte) ((crc >>> 8) & 0xFF),
                (byte) ((crc >>> 16) & 0xFF),
                (byte) ((crc >>> 24) & 0xFF),
        };
    }

    // ----------------------------------------------------------------- utilities

    /**
     * Compares two byte arrays without an early exit.
     *
     * <p>Used for every MAC check. A comparison that returns as soon as it
     * finds a difference leaks, through its timing, how many leading bytes an
     * attacker guessed right, which is enough to forge a MAC byte by byte.
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < a.length; i++) {
            difference |= a[i] ^ b[i];
        }
        return difference == 0;
    }

    /** Concatenates byte arrays, skipping nulls. */
    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            if (part != null) {
                total += part.length;
            }
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            if (part != null) {
                System.arraycopy(part, 0, out, offset, part.length);
                offset += part.length;
            }
        }
        return out;
    }

    /** Encodes a signed 32-bit integer as four little-endian bytes. */
    public static byte[] int32ToLittleEndian(int value) {
        return new byte[] {
                (byte) (value & 0xFF),
                (byte) ((value >>> 8) & 0xFF),
                (byte) ((value >>> 16) & 0xFF),
                (byte) ((value >>> 24) & 0xFF),
        };
    }

    /** Decodes four little-endian bytes at {@code offset} as a signed 32-bit integer. */
    public static int int32FromLittleEndian(byte[] data, int offset) {
        if (data.length < offset + 4) {
            throw new IllegalArgumentException(
                    "need 4 bytes at offset " + offset + ", have " + data.length);
        }
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void require(byte[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(
                    name + " must be " + length + " bytes, got "
                            + (value == null ? "null" : value.length));
        }
    }
}
