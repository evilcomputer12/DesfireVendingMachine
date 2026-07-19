package com.midnightbrewer.reference.desfire.crypto;

/**
 * AES-CMAC as specified by RFC 4493, plus the 8-byte truncation DESFire uses.
 *
 * <p>The JDK has no CMAC, and the brief forbids adding BouncyCastle, so this is
 * a direct implementation of the RFC. It is short enough to read in one sitting
 * and is pinned against all four published RFC 4493 vectors as well as against
 * {@code df_cmac} from {@code desfire_crypto.c} -- which, having compiled and
 * run it, produces exactly the RFC values, confirming the C is standard CMAC
 * and not a variant.
 *
 * <p>The interesting part is {@link #truncate}. DESFire does not send the full
 * 16-byte CMAC; it sends the eight odd-indexed bytes. That is the whole
 * mechanism, and it is the single easiest thing to get wrong -- taking the
 * first eight bytes instead produces a MAC the card rejects with no useful
 * diagnostic.
 */
public final class AesCmac {

    /** Length of a full CMAC, in bytes. */
    public static final int MAC_LENGTH = 16;

    /** Length of the truncated MAC DESFire puts on the wire, in bytes. */
    public static final int TRUNCATED_LENGTH = 8;

    /**
     * The constant used to reduce the subkeys back into the field, for the
     * 128-bit block size. RFC 4493 calls it {@code Rb}; only its low byte is
     * non-zero, so only that byte is ever applied.
     */
    private static final int RB = 0x87;

    private AesCmac() {
    }

    /**
     * Computes the full 16-byte AES-CMAC of {@code message} under {@code key}.
     *
     * <p>An empty message is valid and has a defined MAC -- RFC 4493's first
     * test vector -- so it is not rejected.
     */
    public static byte[] calculate(byte[] key, byte[] message) {
        byte[] data = message == null ? new byte[0] : message;

        // Subkey generation (RFC 4493 section 2.3): L = AES-128(K, 0^128),
        // then K1 and K2 by successive shift-and-conditional-xor.
        byte[] l = Aes.ecbEncryptBlock(key, new byte[Aes.BLOCK_SIZE]);
        byte[] k1 = subkey(l);
        byte[] k2 = subkey(k1);

        int blocks = (data.length + Aes.BLOCK_SIZE - 1) / Aes.BLOCK_SIZE;
        boolean lastBlockComplete = data.length > 0 && data.length % Aes.BLOCK_SIZE == 0;
        if (blocks == 0) {
            blocks = 1;
        }

        // The final block is padded (unless it is already full) and mixed with
        // K1 or K2. Which subkey is used is precisely what stops an attacker
        // moving the padding boundary.
        byte[] last = new byte[Aes.BLOCK_SIZE];
        if (lastBlockComplete) {
            System.arraycopy(data, (blocks - 1) * Aes.BLOCK_SIZE, last, 0, Aes.BLOCK_SIZE);
            xorInto(last, k1);
        } else {
            int remainder = data.length % Aes.BLOCK_SIZE;
            if (remainder > 0) {
                System.arraycopy(data, (blocks - 1) * Aes.BLOCK_SIZE, last, 0, remainder);
            }
            last[remainder] = (byte) 0x80;
            xorInto(last, k2);
        }

        // CBC-MAC over the leading full blocks with a zero IV, then absorb the
        // prepared final block.
        byte[] x = new byte[Aes.BLOCK_SIZE];
        for (int block = 0; block < blocks - 1; block++) {
            byte[] scratch = new byte[Aes.BLOCK_SIZE];
            for (int i = 0; i < Aes.BLOCK_SIZE; i++) {
                scratch[i] = (byte) (data[block * Aes.BLOCK_SIZE + i] ^ x[i]);
            }
            x = Aes.ecbEncryptBlock(key, scratch);
        }
        xorInto(x, last);
        return Aes.ecbEncryptBlock(key, x);
    }

    /**
     * The eight odd-indexed bytes of a CMAC: {@code mac[1], mac[3], ... mac[15]}.
     *
     * <p>Port of {@code df_truncate_mac}. This is what DESFire EV2 appends to a
     * command and what it returns with a response; the even bytes are never
     * transmitted.
     */
    public static byte[] truncate(byte[] mac) {
        if (mac == null || mac.length != MAC_LENGTH) {
            throw new IllegalArgumentException(
                    "CMAC to truncate must be " + MAC_LENGTH + " bytes, got "
                            + (mac == null ? "null" : mac.length));
        }
        byte[] out = new byte[TRUNCATED_LENGTH];
        for (int i = 0; i < TRUNCATED_LENGTH; i++) {
            out[i] = mac[i * 2 + 1];
        }
        return out;
    }

    /** {@link #calculate} followed by {@link #truncate}, which is the only pairing used. */
    public static byte[] calculateTruncated(byte[] key, byte[] message) {
        return truncate(calculate(key, message));
    }

    /**
     * One subkey derivation step: left-shift by one bit, and if the input's top
     * bit was set, XOR the low byte with {@code Rb}.
     *
     * <p>Port of {@code _gen_subkey}.
     */
    private static byte[] subkey(byte[] input) {
        byte[] out = new byte[Aes.BLOCK_SIZE];
        int carry = 0;
        for (int i = Aes.BLOCK_SIZE - 1; i >= 0; i--) {
            int value = input[i] & 0xFF;
            out[i] = (byte) ((value << 1) | carry);
            carry = (value >>> 7) & 1;
        }
        if ((input[0] & 0x80) != 0) {
            out[Aes.BLOCK_SIZE - 1] ^= (byte) RB;
        }
        return out;
    }

    private static void xorInto(byte[] target, byte[] operand) {
        for (int i = 0; i < target.length; i++) {
            target[i] ^= operand[i];
        }
    }
}
