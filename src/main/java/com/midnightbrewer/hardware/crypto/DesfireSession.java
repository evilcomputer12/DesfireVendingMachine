package com.midnightbrewer.hardware.crypto;

import java.util.Arrays;

public class DesfireSession {
    private final byte[] keyEnc;
    private final byte[] keyMac;

    private enum SessionKeyType {
        ENC,
        MAC
    }

    public DesfireSession(byte[] authKey, byte[] rndA, byte[] rndB) {
        if(authKey.length != 16) {
            throw new IllegalArgumentException("Authentication key must be 16 bytes for AES-128");
        }
        this.keyEnc = deriveSessionKey(authKey, rndA, rndB, SessionKeyType.ENC);
        this.keyMac = deriveSessionKey(authKey, rndA, rndB, SessionKeyType.MAC);
    }

    private static byte[] deriveSessionKey(byte[] authKey, byte[] rndA, byte[] rndB, SessionKeyType isEncKeyOrMacKey) {
        byte[] SV = new byte[32];
        byte[] SK = new byte[16];
        
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
        System.arraycopy(sk, 0, SK, 0, SK.length);
        return SK.clone();
    }
    public byte[] encKey() { 
        return keyEnc.clone(); 
    }
    public byte[] macKey() { 
        return keyMac.clone(); 
    }
}
