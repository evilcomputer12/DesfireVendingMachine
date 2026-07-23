package com.midnightbrewer.hardware;

import com.midnightbrewer.hardware.crypto.Aes;
import com.midnightbrewer.hardware.crypto.DesfireSession;
import java.util.Arrays;

import java.security.SecureRandom;


public class DesfireCard {
    private final ApduChannel channel;//HAS-A channel (composition);
    private byte[] TI; // Transaction Identifier
    private DesfireSession session; // Session object to hold the derived session keys
    private final RandomSource randomSource;
    
    public DesfireCard(ApduChannel channel, RandomSource randomSource) {
        this.channel = channel;
        this.randomSource = randomSource;
    }

    private byte[] wrapApdu(byte cmd, byte[] data) throws SpiException {
        byte[] apdu =  new byte[data.length+6];
        apdu[0] = (byte) 0x90; // CLA
        apdu[1] = cmd;        // INS
        apdu[2] = 0x00;       // P1
        apdu[3] = 0x00;       // P2
        apdu[4] = (byte) data.length; // Lc
        System.arraycopy(data, 0, apdu, 5, data.length);
        apdu[apdu.length-1] = 0x00; // Le
        return channel.transceive(apdu);
    }

     private static byte[] rotateLeft(byte[] array) {
        byte[] rotated = new byte[array.length];
        System.arraycopy(array, 1, rotated, 0, array.length - 1);
        rotated[rotated.length - 1] = array[0];
        return rotated;
    }

    public void selectApplication(byte[] aid) throws SpiException {
        if(aid.length != 3) {
            throw new IllegalArgumentException("AID must be 3 bytes");
        }
        byte[] apduResponse = wrapApdu((byte) 0x5A, aid);
        if(apduResponse.length < 2) {
            throw new SpiException("Select application failed: with response " + Arrays.toString(apduResponse));
        }
    }

    public void authenticateEv2First(byte keyNo, byte[] key) throws SpiException {
        
        if(key.length != 16) {
            throw new IllegalArgumentException("Key must be 16 bytes for AES-128");
        }
        // Step 1: Send the Authenticate EV2 First command
        byte[] apduResponse = wrapApdu((byte) 0x71, new byte[]{keyNo, 0x00}); //the keyNo plus a "length of capabilities" byte, which is 0x00 for EV2First
        if(apduResponse.length < 2) {
            throw new SpiException("Authentication failed: with response " + Arrays.toString(apduResponse));
        }
        byte[] encRndb = Arrays.copyOfRange(apduResponse, 0, apduResponse.length - 2); // Exclude the last two status bytes
        byte[] iv = new byte[16];
        byte[] rndB = Aes.cbcDecrypt(key, iv, encRndb); 
        byte[] rndBRotated = rotateLeft(rndB);
        byte[] rndArndBRot = new byte[32];
        byte[] rndA = randomSource.nextBytes(16);
        System.arraycopy(rndA, 0, rndArndBRot, 0, 16);
        System.arraycopy(rndBRotated, 0, rndArndBRot, 16, 16);
        iv = new byte[16];
        byte[] encRndArndBRot = Aes.cbcEncrypt(key, iv, rndArndBRot);
        byte[] apduResponse2 = wrapApdu((byte) 0xAF, encRndArndBRot);
        if(apduResponse2.length < 2) {
            throw new SpiException("Authentication failed: with response " + Arrays.toString(apduResponse2));
        }
        byte[] encResponse = Arrays.copyOfRange(apduResponse2, 0, apduResponse2.length - 2); // Exclude the last two status bytes
        iv = new byte[16];
        byte[] decryptedResponse = Aes.cbcDecrypt(key, iv, encResponse);
        byte[] rndARotated = Arrays.copyOfRange(decryptedResponse, 4, 20); // Extract the last 16 bytes (rotated RndA)
        byte[] expectedRndARotated = rotateLeft(rndA);
        if (!Arrays.equals(rndARotated, expectedRndARotated)) {
            throw new SpiException("Authentication failed: RndA mismatch");
        }
        TI = Arrays.copyOfRange(decryptedResponse, 0, 4); // Store the first 4 bytes as TI
        session = new DesfireSession(key, rndA, rndB);
    }
    public byte[] getTi() {
        return TI;
    }

}