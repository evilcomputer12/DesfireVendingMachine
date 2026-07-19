package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.desfire.crypto.DesfireCrypto;
import com.midnightbrewer.reference.util.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The EV2 secure-messaging session pinned, byte for byte, against vectors from
 * the compiled reference C.
 *
 * <p>These are the load-bearing tests. A test that drove a simulated card built
 * from the same primitives would only prove the code is self-consistent; these
 * instead reproduce the exact command MACs, response MACs, IVs and ciphertexts
 * that {@code desfire_cmd.c}'s {@code _calc_cmd_mac}, {@code _verify_resp_mac},
 * {@code _calc_iv_cmd}, {@code _calc_iv_resp} and its Full-mode encryption
 * produce for a fixed session (sessKeyEnc = 50..5F, sessKeyMac = 60..6F,
 * TI = DEADBEEF), so a divergence in MAC input layout, IV construction or -- the
 * classic mistake -- the point at which the counter increments will fail here.
 */
class DesfireSessionTest {

    private static DesfireSession sessionAtCounter(int counter) {
        DesfireSession session = new DesfireSession(
                ramp(0x50, 16), ramp(0x60, 16), Hex.decode("DEADBEEF"), 0);
        for (int i = 0; i < counter; i++) {
            session.incrementCounter();
        }
        return session;
    }

    @Test
    void commitCommandMacAtCounterThreeMatchesC() {
        assertArrayEquals(Hex.decode("FE9CE48CCF85F7A1"),
                sessionAtCounter(3).commandMac(DesfireCommand.COMMIT_TRANSACTION, null));
    }

    @Test
    void commitResponseMacAtCounterFourMatchesC() {
        // The response MAC uses the post-increment counter -- counter 4 here.
        assertArrayEquals(Hex.decode("A11B4557E15F978C"),
                sessionAtCounter(4).responseMac(null));
    }

    @Test
    void abortCommandMacAtCounterSevenMatchesC() {
        assertArrayEquals(Hex.decode("0A9433668539E298"),
                sessionAtCounter(7).commandMac(DesfireCommand.ABORT_TRANSACTION, null));
    }

    @Test
    void getValueCommandMacAtCounterOneMatchesC() {
        assertArrayEquals(Hex.decode("55DBC4EE62E92288"),
                sessionAtCounter(1).commandMac(DesfireCommand.GET_VALUE, new byte[] {0x02}));
    }

    @Test
    void responseIvAtCounterTwoMatchesC() {
        assertArrayEquals(Hex.decode("E64C41A50AC17A03D68AFF9A86C67DF1"),
                sessionAtCounter(2).responseIv());
    }

    @Test
    void getValueResponseDecryptsToTheBalance() {
        DesfireSession session = sessionAtCounter(2);
        byte[] ciphertext = Hex.decode("4D887A40D604B0EA2206700BFEBBC3A4");
        byte[] plain = session.decryptResponseData(ciphertext);
        assertEquals(2500, DesfireCrypto.int32FromLittleEndian(plain, 0));
    }

    @Test
    void getValueResponseMacVerifies() {
        DesfireSession session = sessionAtCounter(2);
        byte[] ciphertext = Hex.decode("4D887A40D604B0EA2206700BFEBBC3A4");
        assertTrue(session.verifyResponseMac(ciphertext, Hex.decode("9D143AAB72D0995D")));
        assertFalse(session.verifyResponseMac(ciphertext, Hex.decode("0000000000000000")));
    }

    @Test
    void debitCommandIvCiphertextAndMacMatchC() {
        // Full-mode Debit of 350 at counter 5: the command IV, the encrypted
        // 4-byte amount, and the command MAC over [fileNo || ciphertext].
        DesfireSession session = sessionAtCounter(5);
        assertArrayEquals(Hex.decode("042B75C05716AC0172CD96B5D501C3BA"), session.commandIv());

        byte[] ciphertext = session.encryptCommandData(DesfireCrypto.int32ToLittleEndian(350));
        assertArrayEquals(Hex.decode("C6297A069825F908CA03D874C3B016CC"), ciphertext);

        byte[] macInput = DesfireCrypto.concat(new byte[] {0x02}, ciphertext);
        assertArrayEquals(Hex.decode("39E7FA6649327F6E"),
                session.commandMac(DesfireCommand.DEBIT, macInput));
    }

    @Test
    void createValueCommandMacAtCounterZeroMatchesC() {
        byte[] payload = Hex.decode("020320220000000040420F00C409000000");
        assertArrayEquals(Hex.decode("E3D65B9106AC79EF"),
                sessionAtCounter(0).commandMac(DesfireCommand.CREATE_VALUE_FILE, payload));
    }

    @Test
    void counterWrapsAtSixteenBits() {
        DesfireSession session = sessionAtCounter(0);
        for (int i = 0; i < 0x10000; i++) {
            session.incrementCounter();
        }
        assertEquals(0, session.commandCounter());
    }

    private static byte[] ramp(int start, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (start + i);
        }
        return out;
    }
}
