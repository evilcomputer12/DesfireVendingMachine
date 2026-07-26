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
        session = new DesfireSession(key, rndA, rndB, TI);
    }
    public byte[] getTi() {
        return TI;
    }

    // ═══ M7d-2 : read the balance (GetValue 0x6C, CommMode.Full) ═══════
    // Command  : 90 6C 00 00 09 [fileNo][cmdMac 8] 00
    // Response : [encrypted 16][respMac 8] 91 00
    // Returns the balance in cents.
    public int getValue(byte fileNo) throws SpiException {
        // TODO 1: build + send the command.
        //   byte[] header = { fileNo };
        //   byte[] mac    = session.commandMac((byte) 0x6C, header);   // uses ctr (0)
        //   byte[] cmdData = header ++ mac   (9 bytes: fileNo then the 8 mac bytes)
        //   byte[] response = wrapApdu((byte) 0x6C, cmdData);

        byte[] header = { fileNo };
        byte[] mac = session.commandMac((byte) 0x6C, header);
        byte[] cmdData = Arrays.copyOf(header, header.length + mac.length);
        System.arraycopy(mac, 0, cmdData, header.length, mac.length);
        byte[] response = wrapApdu((byte) 0x6C, cmdData);

        // TODO 2: card accepted it -> tick the counter BEFORE reading the response
        //   (response MAC + response IV both use the incremented value).
        //   session.incrementCounter(); 
        session.incrementCounter();

        

        // TODO 3: split the response. Drop the last 2 status bytes, then:
        //   body must be >= 24 bytes; encrypted = body[0..16], respMac = body[16..24].
        response = Arrays.copyOf(response, response.length - 2); // Drop the last 2 status bytes
        if (response.length < 24) {
            throw new SpiException("GetValue response too short: " + Arrays.toString(response));
        }
        byte[] encrypted = Arrays.copyOfRange(response, 0, 16);
        byte[] respMac = Arrays.copyOfRange(response, 16, 24);


        // TODO 4: verify integrity, refuse to trust the balance otherwise.
        //   if (!session.verifyResponseMac(encrypted, respMac))
        //       throw new SpiException("GetValue response MAC failed");
        if (!session.verifyResponseMac(encrypted, respMac)) {
            throw new SpiException("GetValue response MAC failed");
        }

        // TODO 5: decrypt and decode.
        //   byte[] plain = session.decryptResponseData(encrypted);
        //   return decodeValue(plain);   // first 4 bytes, little-endian signed
        byte[] plain = session.decryptResponseData(encrypted);
        return decodeValue(plain);
    }

    // Reads a signed 32-bit little-endian int from the first 4 bytes.
    private static int decodeValue(byte[] b) {
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8)
             | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    // ═══ M7d-3 : spend money (Debit 0xDC + CommitTransaction 0xC7) ═════
    // Debit STAGES a change; nothing moves until commitTransaction() succeeds.
    // Debit is CommMode.Full (amount encrypted); Commit is MAC-only (no data).

    /**
     * Stage a debit of {@code amountCents} on the value file.
     * Command : 90 DC 00 00 19 [fileNo][enc amount 16][cmdMac 8] 00
     * Response: [respMac 8] 91 00
     */
    public void debit(byte fileNo, int amountCents) throws SpiException {
        // TODO 1: build + send.
        //   byte[] header     = { fileNo };
        //   byte[] ciphertext = session.encryptCommandData(encodeValue(amountCents)); // 16 bytes, uses ctr
        //   byte[] macInput   = header ++ ciphertext;    // fileNo, then the 16 cipher bytes
        //   byte[] mac        = session.commandMac((byte) 0xDC, macInput);
        //   byte[] response   = wrapApdu((byte) 0xDC, macInput ++ mac);

        byte[] header = { fileNo };
        byte[] ciphertext = session.encryptCommandData(encodeValue(amountCents));
        byte[] macInput = Arrays.copyOf(header, header.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, macInput, header.length, ciphertext.length);
        byte[] mac = session.commandMac((byte) 0xDC, macInput);
        byte[] cmdData = Arrays.copyOf(macInput, macInput.length + mac.length);
        System.arraycopy(mac, 0, cmdData, macInput.length, mac.length);
        byte[] response = wrapApdu((byte) 0xDC, cmdData);
        // TODO 2: requireOk(response);   // e.g. 91 BE = not enough balance
        //         session.incrementCounter();
        requireOk(response);
        session.incrementCounter();


        // TODO 3: body = response minus last 2 status bytes; need >= 8;
        //         respMac = last 8 bytes; verify over EMPTY data:
        //         if (!session.verifyResponseMac(new byte[0], respMac))
        //             throw new SpiException("Debit response MAC failed");
        byte[] respMac = Arrays.copyOfRange(response, response.length - 10, response.length - 2);
        if (!session.verifyResponseMac(new byte[0], respMac)) {
            throw new SpiException("Debit response MAC failed");
        }
    }

    /**
     * Make the staged debit permanent.
     * Command : 90 C7 00 00 08 [cmdMac 8] 00
     * Response: [respMac 8] 91 00
     */
    public void commitTransaction() throws SpiException {
        // TODO 1: byte[] mac      = session.commandMac((byte) 0xC7, new byte[0]);
        //         byte[] response = wrapApdu((byte) 0xC7, mac);
        byte[] mac = session.commandMac((byte) 0xC7, new byte[0]);
        byte[] response = wrapApdu((byte) 0xC7, mac);
        // TODO 2: requireOk(response); session.incrementCounter();
        requireOk(response);
        session.incrementCounter();
        // TODO 3: verify the response MAC over empty data, exactly like debit.
        byte[] respMac = Arrays.copyOfRange(response, response.length - 10, response.length - 2);
        if (!session.verifyResponseMac(new byte[0], respMac)) {
            throw new SpiException("CommitTransaction response MAC failed");
        }
    }

    // Encodes a signed 32-bit value as 4 little-endian bytes (inverse of decodeValue).
    private static byte[] encodeValue(int v) {
        return new byte[] { (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) };
    }

    // Throws if the trailing status word is not 91 00 (surfaces the card's code).
    private static void requireOk(byte[] response) throws SpiException {
        if (response.length < 2) {
            throw new SpiException("Card response too short");
        }
        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;
        if (sw1 != 0x91 || sw2 != 0x00) {
            throw new SpiException(String.format("Card returned status %02X%02X", sw1, sw2));
        }
    }
}