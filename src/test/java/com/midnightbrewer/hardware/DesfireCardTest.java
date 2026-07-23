package com.midnightbrewer.hardware;

import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

// The test IS its own fake card + fake RNG: it implements both seams, so
// new DesfireCard(this, this) plugs the test straight into the card.
public class DesfireCardTest implements RandomSource, ApduChannel {

    /*
    Full replay (fixed nonces — offline handshake test, fake channel + fixed RndA):
    key      = 00112233445566778899aabbccddeeff
    RndA     = a0a1a2a3a4a5a6a7a8a9aaabacadaeaf   (pinned by nextBytes)
    RndB     = b0b1b2b3b4b5b6b7b8b9babbbcbdbebf

    card step-1 payload (encRndB)  = eab6822fe368d5bc9895fb2558b38dde
    you send in step 2  (encAB)    = cf086a82c0b745a749daabb28a7a8db332272f9e5fdc37a4746064f25b7c2ca7
    card step-2 payload (encResp)  = 4e08e447d74810052caa1c5b6c60343f51f6e901ad171e677b100d3a34c925e4
    → extracted TI                 = 11223344
    */

    byte[] key   = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
    byte[] rndA  = HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
    byte[] encAB = HexFormat.of().parseHex("cf086a82c0b745a749daabb28a7a8db332272f9e5fdc37a4746064f25b7c2ca7");
    byte[] tiExpected = HexFormat.of().parseHex("11223344");

    // Replies carry the trailing status word (91af / 9100), because the card
    // strips the last 2 bytes off every reply.
    byte[] selectReply  = HexFormat.of().parseHex("9100");
    byte[] step1Reply   = HexFormat.of().parseHex("eab6822fe368d5bc9895fb2558b38dde" + "91af");
    byte[] step2Reply   = HexFormat.of().parseHex("4e08e447d74810052caa1c5b6c60343f51f6e901ad171e677b100d3a34c925e4" + "9100");

    @Override
    public byte[] nextBytes(int n) {
        return rndA; // pin RndA so the handshake is deterministic
    }

    @Override
    public byte[] transceive(byte[] apdu) throws SpiException {
        // dispatch on the INS byte (apdu[1]) — robust to keyNo and Lc.
        switch (apdu[1]) {
            case (byte) 0x5A: // SelectApplication
                return selectReply;
            case (byte) 0x71: // AuthenticateEV2First, step 1
                return step1Reply;
            case (byte) 0xAF: // step 2: check what we SENT, then reply
                byte[] sentEncAB = Arrays.copyOfRange(apdu, 5, apdu.length - 1);
                assertArrayEquals(encAB, sentEncAB, "step-2 ciphertext (encAB) is wrong");
                return step2Reply;
            default:
                throw new SpiException("Unexpected APDU: " + Arrays.toString(apdu));
        }
    }

    @Test
    public void authenticateEv2First_matchesReplayVectors() throws SpiException {
        DesfireCard card = new DesfireCard(this, this);

        card.selectApplication(new byte[]{0x01, 0x02, 0x03});
        card.authenticateEv2First((byte) 0x00, key); // no throw == RndA' verified

        assertArrayEquals(tiExpected, card.getTi(),
                "Transaction Identifier (TI) does not match expected value");
    }
}
