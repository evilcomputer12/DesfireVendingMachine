package com.midnightbrewer.hardware.crypto;

/**
 * AES-CMAC (RFC 4493) -- the message authentication code DESFire EV2 uses.
 *
 * The JDK has no CMAC, so this is the from-scratch part. But you're not
 * reimplementing AES: you build the CMAC construction on top of Aes.ecbEncrypt.
 *
 * OOP on show: the key and the two derived subkeys (K1, K2) are computed ONCE
 * in the constructor and kept private -- encapsulated state, an invariant set
 * up at birth. compute() is the single public operation, and it never changes
 * that state. That's a clean, immutable crypto object.
 *
 * The algorithm (RFC 4493) has two parts, in this file as two placeholders:
 *   - generateSubkeys()  §2.3   derive K1, K2 from the key
 *   - compute()          §2.4   the MAC over a message
 */
public final class AesCmac {

    private static final int BLOCK = 16;
    private static final byte RB = (byte) 0x87; // the CMAC constant (from the AES poly)

    private final byte[] key;
    private final byte[] k1;
    private final byte[] k2;

    public AesCmac(byte[] key) {
        if (key.length != BLOCK) {
            throw new IllegalArgumentException("AES-128 key must be 16 bytes");
        }
        this.key = key.clone();
        byte[][] subkeys = generateSubkeys(key);
        this.k1 = subkeys[0];
        this.k2 = subkeys[1];
    }

    // ═════════════════════════════════════════════════════════════════
    // RFC 4493 §2.3 -- subkey generation.
    //
    //   L  = AES(K, 0^128)                 // encrypt an all-zero block
    //   K1 = L << 1;  if MSB(L)  set: K1 ^= Rb   (Rb = 0x00..0087)
    //   K2 = K1 << 1; if MSB(K1) set: K2 ^= Rb
    //
    // "MSB set" means the top bit of the first byte (x[0] & 0x80).
    // "^= Rb" means XOR the LAST byte with 0x87 (that's what Rb is).
    // ═════════════════════════════════════════════════════════════════
    private static byte[][] generateSubkeys(byte[] key) {
        // TODO 1: byte[] l = Aes.ecbEncrypt(key, new byte[BLOCK]);   // L = AES(K, 0^128)
        byte[] l = Aes.ecbEncrypt(key, new byte[BLOCK]);   // L = AES(K, 0^128)

        // TODO 2: byte[] k1 = leftShift(l);
        //         if ((l[0] & 0x80) != 0) k1[15] ^= RB;
        byte[] k1 = leftShift(l);
        if((l[0] & 0x80) != 0)
        {
            k1[15] ^= RB;
        }

        byte[] k2 = leftShift(k1);
        if ((k1[0] & 0x80) != 0) {
            k2[15] ^= RB;
        }

        // TODO 4: return new byte[][]{ k1, k2 };
        return new byte[][]{ k1, k2 };
    }

    /**
     * Left-shift a 16-byte block by ONE bit (treating it as one 128-bit number).
     * The bit that falls off byte i+1's top becomes byte i's bottom.
     */
    private static byte[] leftShift(byte[] in) {
        // TODO: byte[] out = new byte[BLOCK];  int carry = 0;
        //       walk from the LAST byte to the first:
        //       for (int i = BLOCK - 1; i >= 0; i--) {
        //           int b = in[i] & 0xFF;
        //           out[i] = (byte) ((b << 1) | carry);
        //           carry = (b >> 7) & 1;      // the bit shifted out of this byte
        //       }
        //       return out;
        byte[] out = new byte[BLOCK];
        int carry = 0;
        for (int i = BLOCK - 1; i >= 0; i--) {
            int b = in[i] & 0xFF;
            out[i] = (byte) ((b << 1) | carry);
            carry = (b >> 7) & 1;      // the bit shifted out of this byte
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════
    // RFC 4493 §2.4 -- the MAC over 'message'. Returns 16 bytes.
    //
    //   split the message into 16-byte blocks M_1 .. M_n
    //   the LAST block gets special treatment:
    //     - if it's a full 16 bytes:  M_last = M_n XOR K1
    //     - otherwise (incl. empty):  pad it (append 0x80 then 0x00s to 16),
    //                                 M_last = padded XOR K2
    //   then CBC-MAC:
    //     X = 0^128
    //     for each block except the last:  X = AES(K, X XOR M_i)
    //     T = AES(K, X XOR M_last)         <- the CMAC
    // ═════════════════════════════════════════════════════════════════
    public byte[] compute(byte[] message) {
        // TODO 1: how many blocks, and is the last one full?
        //   int n; boolean lastComplete;
        //   if (message.length == 0) { n = 1; lastComplete = false; }
        //   else { n = (message.length + BLOCK - 1) / BLOCK;              // ceil
        //          lastComplete = (message.length % BLOCK == 0); }
        int n;
        boolean lastComplete;
        if(message.length == 0)
        {
            n = 1;
            lastComplete = false;
        }
        else
        {
            n = (message.length + BLOCK - 1) / BLOCK;              // ceil
            lastComplete = (message.length % BLOCK == 0);
        }

        // TODO 2: build M_last.
        //   Grab the last block's bytes (there may be fewer than 16 if !lastComplete).
        //   if lastComplete: mLast = Aes.xor(lastBlock, k1)
        //   else:            byte[] padded = new byte[BLOCK];
        //                    copy the leftover bytes in; padded[leftoverLen] = (byte)0x80;
        //                    mLast = Aes.xor(padded, k2)
        byte[] mLast;
        if(lastComplete)
        {
            byte[] lastBlock = new byte[BLOCK];
            System.arraycopy(message, (n - 1) * BLOCK, lastBlock, 0, BLOCK);
            mLast = Aes.xor(lastBlock, k1);
        }
        else
        {
            byte[] padded = new byte[BLOCK];
            int leftover = message.length % BLOCK;
            System.arraycopy(message, (n - 1) * BLOCK, padded, 0, leftover);
            padded[leftover] = (byte) 0x80;
            mLast = Aes.xor(padded, k2);
        }
        // TODO 3: CBC-MAC through the blocks.
        //   byte[] x = new byte[BLOCK];
        //   for (int i = 0; i < n - 1; i++) {
        //       byte[] block = the i-th 16-byte slice of message;
        //       x = Aes.ecbEncrypt(key, Aes.xor(x, block));
        //   }
        //   return Aes.ecbEncrypt(key, Aes.xor(x, mLast));

        byte[] x = new byte[BLOCK];
        for (int i = 0; i < n - 1; i++) {
            byte[] block = new byte[BLOCK];
            System.arraycopy(message, i * BLOCK, block, 0, BLOCK);
            x = Aes.ecbEncrypt(key, Aes.xor(x, block));
        }
        return Aes.ecbEncrypt(key, Aes.xor(x, mLast));
    }
}
