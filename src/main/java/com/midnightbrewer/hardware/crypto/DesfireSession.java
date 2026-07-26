package com.midnightbrewer.hardware.crypto;

import java.util.Arrays;

public class DesfireSession {
    private final byte[] keyEnc;
    private final byte[] keyMac;

    // ── EV2 secure-messaging state (M7d) ──
    private final byte[] ti;   // transaction id from auth, 4 bytes
    private int cmdCounter;    // starts at 0, ticks up one per command

    private enum SessionKeyType {
        ENC,
        MAC
    }

    // M7b constructor: derive the keys only. TI defaults to zero -- key
    // derivation never uses it, so the M7b key-derivation test still passes.
    // (Constructor chaining: this(...) calls the 4-arg constructor below.)
    public DesfireSession(byte[] authKey, byte[] rndA, byte[] rndB) {
        this(authKey, rndA, rndB, new byte[4]);
    }

    // M7d constructor: keys + the transaction id, ready for secure messaging.
    public DesfireSession(byte[] authKey, byte[] rndA, byte[] rndB, byte[] ti) {
        if(authKey.length != 16) {
            throw new IllegalArgumentException("Authentication key must be 16 bytes for AES-128");
        }
        if(ti.length != 4) {
            throw new IllegalArgumentException("TI must be 4 bytes");
        }
        this.keyEnc = deriveSessionKey(authKey, rndA, rndB, SessionKeyType.ENC);
        this.keyMac = deriveSessionKey(authKey, rndA, rndB, SessionKeyType.MAC);
        this.ti = ti.clone();
        this.cmdCounter = 0;
    }

    private static byte[] deriveSessionKey(byte[] authKey, byte[] rndA, byte[] rndB, SessionKeyType isEncKeyOrMacKey) {
        byte[] SV = new byte[32];
        if(rndA.length != 16 || rndB.length != 16) {
            throw new IllegalArgumentException("Random numbers must be 16 bytes for AES-128");
        }
        //0xA5 for ENC key and 0x5A for MAC key
        int cnt = 0;
        SV[cnt++] = isEncKeyOrMacKey == SessionKeyType.ENC ? (byte)(0xA5 & 0xFF) : (byte)(0x5A & 0xFF); // Set the first byte of SV based on key type
        SV[cnt++] = SV[0] == (byte)(0xA5 & 0xFF) ? (byte)(0x5A & 0xFF) : (byte)(0xA5 & 0xFF); // Set the second byte of SV to the opposite value 
        SV[cnt++] = 0x00; // Set the third byte of SV to 0
        SV[cnt++] = 0x01; // Set the fourth byte of SV to 1
        SV[cnt++] = 0x00; // Set the fifth byte of SV to 0
        SV[cnt++] = (byte)(0x80 & 0xFF); // Set the sixth byte of SV to 0x80
        SV[cnt++] = rndA[0]; // Set the seventh byte of SV to the first byte of rndA
        SV[cnt++] = rndA[1]; // Set the eighth byte of SV to the second byte of rndA
        
        byte[] partA = Arrays.copyOfRange(rndA, 2, 8);
        byte[] partB = Arrays.copyOfRange(rndB, 0, 6);
        byte[] mixedRnd = Aes.xor(partA, partB);
        System.arraycopy(mixedRnd, 0, SV, cnt, mixedRnd.length);
        cnt += mixedRnd.length;
        System.arraycopy(rndB, 6, SV, cnt, 10); // Copy the remaining 10 bytes of rndB to SV
        cnt += 10;
        System.arraycopy(rndA, 8, SV, cnt, 8); // Copy the remaining 8 bytes of rndA to SV
        cnt += 8;
        byte[] sk = new AesCmac(authKey).compute(SV); // Derive the session key using AES-CMAC with the authentication key and SV
        return sk;
    }
    // ═══ M7d-1a : the MAC layer ══════════════════════════════════════
    // Fill these in. Stubs return placeholder bytes so the project compiles;
    // verify against the vectors before wiring anything to the card.

    /**
     * After the card accepts a command the counter ticks up (wraps at 16 bits).
     * The response MAC and response IV use the incremented value -- ordering is
     * load-bearing.
     */
    public void incrementCounter() {
        // TODO: cmdCounter = (cmdCounter + 1) & 0xFFFF;
        cmdCounter = (cmdCounter + 1) & 0xFFFF;
    }

    /**
     * DESFire transmits only 8 of the 16 CMAC bytes: the ODD-indexed ones
     * mac[1], mac[3], mac[5], ... mac[15].
     */
    private static byte[] truncateMac(byte[] mac16) {
        // TODO: byte[] out = new byte[8];
        //       for (int i = 0; i < 8; i++) out[i] = mac16[i * 2 + 1];
        //       return out;
        byte[] out = new byte[8];
        for(int i = 0; i < 8; i++){
            out[i] = mac16[i * 2 + 1];
        }
        return out;
    }

    /**
     * 8-byte CMAC that rides along with a command.
     * MAC input = [cmd][ctrLo][ctrHi][TI0..TI3][data...]   (ctr is 2 bytes, LE).
     * Then CMAC(keyMac, input) and truncate.  (Callers pass new byte[0] for no data.)
     */
    public byte[] commandMac(byte cmd, byte[] data) {
        // TODO: byte[] input = new byte[7 + data.length];
        //       input[0] = cmd;
        //       input[1] = (byte)(cmdCounter & 0xFF);
        //       input[2] = (byte)((cmdCounter >> 8) & 0xFF);
        //       System.arraycopy(ti, 0, input, 3, 4);
        //       System.arraycopy(data, 0, input, 7, data.length);
        //       return truncateMac(new AesCmac(keyMac).compute(input));
        byte[] input = new byte[7 + data.length];
        input[0] = cmd;
        input[1] = (byte)(cmdCounter & 0xFF);
        input[2] = (byte)(cmdCounter >> 8 & 0xFF);
        System.arraycopy(ti, 0, input, 3, 4);
        System.arraycopy(data, 0, input, 7, data.length);
        return truncateMac(new AesCmac(keyMac).compute(input));
    }

    /**
     * Expected 8-byte CMAC over a response.
     * MAC input = [0x00][ctrLo][ctrHi][TI0..TI3][data...]  (0x00 = success code).
     */
    public byte[] responseMac(byte[] data) {
        // TODO: same shape as commandMac, but input[0] = 0x00.
        byte[] input = new byte[7 + data.length];
        input[0] = 0x00;
        input[1] = (byte)(cmdCounter & 0xFF);
        input[2] = (byte)((cmdCounter >> 8) & 0xFF);
        System.arraycopy(ti, 0, input, 3, 4);
        System.arraycopy(data, 0, input, 7, data.length);
        return truncateMac(new AesCmac(keyMac).compute(input));
    }

    /** True if the card's 8-byte response MAC matches what we compute. */
    public boolean verifyResponseMac(byte[] data, byte[] mac8) {
        // TODO: return Arrays.equals(responseMac(data), mac8);
        return Arrays.equals(responseMac(data), mac8);
    }

    // ═══ M7d-1b : the crypto layer ═══════════════════════════════════
    // IV derivation + ISO-7816 padding + AES-CBC encrypt/decrypt.

    /**
     * IV that encrypts COMMAND data (reader -> card).
     * IV = AES-ECB(keyEnc, [0xA5, 0x5A, TI0..TI3, ctrLo, ctrHi, 00 x8]).
     */
    public byte[] commandIv() {
        // TODO: return calcIv((byte) 0xA5, (byte) 0x5A);
        
        return calcIv((byte) 0xA5, (byte) 0x5A);
    }

    /**
     * IV that decrypts RESPONSE data (card -> reader). Note the label bytes are
     * SWAPPED: 0x5A, 0xA5 instead of 0xA5, 0x5A.
     */
    public byte[] responseIv() {
        // TODO: return calcIv((byte) 0x5A, (byte) 0xA5);
        return calcIv((byte) 0x5A, (byte) 0xA5);
    }

    // Shared IV builder -- only the two label bytes differ between cmd and resp.
    private byte[] calcIv(byte b0, byte b1) {
        // TODO: byte[] block = new byte[16];
        //       block[0] = b0; block[1] = b1;
        //       System.arraycopy(ti, 0, block, 2, 4);
        //       block[6] = (byte)(cmdCounter & 0xFF);
        //       block[7] = (byte)((cmdCounter >> 8) & 0xFF);
        //       return Aes.ecbEncrypt(keyEnc, block);
        byte[] block = new byte[16];
        block[0] = b0; block[1] = b1;
        System.arraycopy(ti, 0, block, 2, 4);
        block[6] = (byte)(cmdCounter & 0xFF);
        block[7] = (byte)((cmdCounter >> 8) & 0xFF);
        return Aes.ecbEncrypt(keyEnc, block);
    }

    /**
     * ISO 7816-4 padding: append 0x80, then 0x00s to the next 16-byte boundary.
     * ALWAYS adds at least one byte (a 16-byte input becomes 32).
     */
    private static byte[] padIso7816(byte[] data) {
        // TODO: int padded = (data.length | 0x0F) + 1;   // next multiple of 16, always > length
        //       byte[] out = new byte[padded];
        //       System.arraycopy(data, 0, out, 0, data.length);
        //       out[data.length] = (byte) 0x80;
        //       return out;
        int padded = (data.length | 0x0F) + 1;   // next multiple of 16, always > length
        byte[] out = new byte[padded];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) 0x80;
        return out;
    }

    /** CommMode.Full: pad the plaintext (ISO 7816-4) then AES-CBC with commandIv. */
    public byte[] encryptCommandData(byte[] plaintext) {
        // TODO: return Aes.cbcEncrypt(keyEnc, commandIv(), padIso7816(plaintext));
        return Aes.cbcEncrypt(keyEnc, commandIv(), padIso7816(plaintext));
    }

    /** CommMode.Full: AES-CBC decrypt response data with responseIv. Padding is
     *  left in place -- the caller reads the 4-byte value off the front. */
    public byte[] decryptResponseData(byte[] ciphertext) {
        // TODO: return Aes.cbcDecrypt(keyEnc, responseIv(), ciphertext);
        return Aes.cbcDecrypt(keyEnc, responseIv(), ciphertext);
    }

    public byte[] encKey() {
        return keyEnc.clone();
    }
    public byte[] macKey() { 
        return keyMac.clone(); 
    }
}
