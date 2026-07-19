package com.midnightbrewer.reference.support;

import com.midnightbrewer.reference.desfire.crypto.Aes;
import com.midnightbrewer.reference.desfire.crypto.AesCmac;
import com.midnightbrewer.reference.desfire.crypto.DesfireCrypto;
import com.midnightbrewer.reference.desfire.crypto.LegacyDes;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.iso14443.ActivatedCard;
import com.midnightbrewer.reference.iso14443.FrameSize;
import com.midnightbrewer.reference.iso14443.Iso14443Transceiver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory DESFire card that models the <em>card</em> side of the EV2
 * protocol, for driving {@link com.midnightbrewer.reference.desfire.DesfireCard}
 * end to end with no hardware.
 *
 * <p>It re-implements the wire format independently of the reader: it runs the
 * AES authentication, verifies every command MAC against its own command
 * counter, decrypts Full-mode command data, applies value-file transactions,
 * and returns responses with their own MACs. Because the counter is advanced on
 * the card at the same point the reader advances it, any disagreement about MAC
 * layout, IV construction or -- above all -- <em>when</em> the counter moves
 * shows up here as an authentication or MAC failure rather than as a silent
 * pass. The primitives themselves are separately pinned to the compiled C, so a
 * shared-bug false pass is covered from the other side.
 *
 * <p>It deliberately answers the two legacy authentications (0x0A / 0x1A) at
 * PICC level and the AES one (0x71), so the reader's factory-auth fallback
 * order is exercised for real.
 *
 * <p>Every command byte that arrives is recorded in {@link #commandLog()}, which
 * is what lets a test assert that {@code FormatPICC} (0xFC) never reaches the
 * wire.
 */
public final class SimulatedEv2Desfire implements Iso14443Transceiver {

    private static final int SW1 = 0x91;
    private static final int OK = 0x00;
    private static final int ADDITIONAL_FRAME = 0xAF;
    private static final int DUPLICATE = 0xDE;
    private static final int APPLICATION_NOT_FOUND = 0xA0;
    private static final int AUTHENTICATION_ERROR = 0xAE;
    private static final int BOUNDARY_ERROR = 0xBE;
    private static final int PERMISSION_DENIED = 0x9D;
    private static final int FILE_NOT_FOUND = 0xF0;
    private static final int LENGTH_ERROR = 0x7E;
    private static final int ILLEGAL_COMMAND = 0x1C;

    private static final byte[] TI = {0x11, 0x22, 0x33, 0x44};

    private final Map<Integer, Application> applications = new HashMap<>();
    private final List<Integer> commandLog = new ArrayList<>();

    private int selectedAid = 0x000000;

    // Pending EV2 authentication (between step 1 and step 2).
    private boolean awaitingEv2Step2;
    private int pendingKeyNo;
    private byte[] pendingKey;
    private byte[] pendingRndB;

    // Pending legacy authentication.
    private boolean awaitingLegacyStep2;
    private byte[] legacyEncRndB;
    private byte[] legacyKey16;

    // Live EV2 session.
    private boolean sessionActive;
    private byte[] sessionKeyEnc;
    private byte[] sessionKeyMac;
    private int commandCounter;
    private int authenticatedKeyNo;

    public SimulatedEv2Desfire() {
        // The PICC-level root application: one AES key, factory-default zeros.
        Application picc = new Application(1);
        applications.put(0x000000, picc);
    }

    /** Adds a pre-existing, already-personalised application (for non-provisioning tests). */
    public Application installApplication(int aid, int numKeys) {
        Application app = new Application(numKeys);
        applications.put(aid, app);
        return app;
    }

    /** Every DESFire command byte received, in order. */
    public List<Integer> commandLog() {
        return List.copyOf(commandLog);
    }

    /** The current stored balance of a value file, bypassing the protocol. */
    public int storedValue(int aid, int fileNo) {
        return applications.get(aid).valueFiles.get(fileNo).value;
    }

    // ------------------------------------------------------------ transceive

    @Override
    public byte[] transceive(byte[] apdu) {
        int cmd = apdu[1] & 0xFF;
        commandLog.add(cmd);
        byte[] data = extractData(apdu);
        try {
            return dispatch(cmd, data);
        } catch (CardError e) {
            return status(e.sw);
        }
    }

    private byte[] dispatch(int cmd, byte[] data) {
        switch (cmd) {
            case 0x5A: return selectApplication(data);
            case 0x0A: return authLegacyStep1(data);
            case 0x1A: return status(ILLEGAL_COMMAND); // card is not in ISO mode
            case 0x71: return authEv2Step1(data);
            case 0xAF: return additionalFrame(data);
            case 0xCA: return createApplication(data);
            case 0xC4: return changeKey(data);
            case 0xCC: return createValueFile(data);
            case 0x6C: return getValue(data);
            case 0x0C: return valueOperation(0x0C, data);
            case 0xDC: return valueOperation(0xDC, data);
            case 0xC7: return commitTransaction(data);
            case 0xA7: return abortTransaction(data);
            case 0xFC: throw new IllegalStateException("FormatPICC (0xFC) must never be sent");
            default:   return status(ILLEGAL_COMMAND);
        }
    }

    // ------------------------------------------------------------ selection

    private byte[] selectApplication(byte[] data) {
        int aid = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16);
        endSession();
        if (!applications.containsKey(aid)) {
            return status(APPLICATION_NOT_FOUND);
        }
        selectedAid = aid;
        return status(OK);
    }

    // ------------------------------------------------------------ legacy auth

    private byte[] authLegacyStep1(byte[] data) {
        endSession();
        Application app = current();
        int keyNo = data[0] & 0xFF;
        legacyKey16 = new byte[16]; // model the factory 2K3DES zero key
        if (keyNo != 0 || !Arrays.equals(app.keys[0], new byte[16])) {
            // Only the factory zero key is reachable via the legacy handshake here.
            return status(AUTHENTICATION_ERROR);
        }
        byte[] rndB = ramp(0xC0, 8);
        byte[] iv0 = new byte[8];
        legacyEncRndB = LegacyDes.tripleDesCbcEncrypt(legacyKey16, iv0, rndB);
        awaitingLegacyStep2 = true;
        this.pendingRndB = rndB;
        return withBody(legacyEncRndB, ADDITIONAL_FRAME);
    }

    private byte[] legacyStep2(byte[] token) {
        awaitingLegacyStep2 = false;
        // Recover rndA || rndB' from the D40 "send" transform:
        //   p[i] = ENC(c[i]) XOR c[i-1], with c[-1] = encRndB.
        byte[] plain = new byte[16];
        byte[] prev = legacyEncRndB;
        for (int off = 0; off < 16; off += 8) {
            byte[] block = Arrays.copyOfRange(token, off, off + 8);
            byte[] enc = LegacyDes.tripleDesEcbEncrypt(legacyKey16, block);
            for (int i = 0; i < 8; i++) {
                plain[off + i] = (byte) (enc[i] ^ prev[i]);
            }
            prev = block;
        }
        byte[] rndBrot = Arrays.copyOfRange(plain, 8, 16);
        if (!Arrays.equals(rndBrot, DesfireCrypto.rotateLeft(pendingRndB))) {
            return status(AUTHENTICATION_ERROR);
        }
        // Legacy auth grants access but installs no EV2 session.
        return status(OK);
    }

    // ------------------------------------------------------------ EV2 auth

    private byte[] authEv2Step1(byte[] data) {
        endSession();
        Application app = current();
        int keyNo = data[0] & 0xFF;
        if (keyNo >= app.keys.length) {
            return status(AUTHENTICATION_ERROR);
        }
        pendingKeyNo = keyNo;
        pendingKey = app.keys[keyNo].clone();
        pendingRndB = ramp(0xB0, 16);
        awaitingEv2Step2 = true;
        byte[] encRndB = Aes.cbcEncrypt(pendingKey, new byte[16], pendingRndB);
        return withBody(encRndB, ADDITIONAL_FRAME);
    }

    private byte[] ev2Step2(byte[] token) {
        awaitingEv2Step2 = false;
        byte[] decrypted = Aes.cbcDecrypt(pendingKey, new byte[16], Arrays.copyOfRange(token, 0, 32));
        byte[] rndA = Arrays.copyOfRange(decrypted, 0, 16);
        byte[] rndBrot = Arrays.copyOfRange(decrypted, 16, 32);
        if (!Arrays.equals(rndBrot, DesfireCrypto.rotateLeft(pendingRndB))) {
            return status(AUTHENTICATION_ERROR);
        }

        // Respond with enc(TI || RndA' || zeros) and install the session.
        byte[] response = new byte[32];
        System.arraycopy(TI, 0, response, 0, 4);
        System.arraycopy(DesfireCrypto.rotateLeft(rndA), 0, response, 4, 16);
        byte[] encResponse = Aes.cbcEncrypt(pendingKey, new byte[16], response);

        sessionKeyEnc = DesfireCrypto.deriveSessionKey(pendingKey, rndA, pendingRndB, DesfireCrypto.LABEL_ENCRYPTION);
        sessionKeyMac = DesfireCrypto.deriveSessionKey(pendingKey, rndA, pendingRndB, DesfireCrypto.LABEL_MAC);
        commandCounter = 0;
        authenticatedKeyNo = pendingKeyNo;
        sessionActive = true;
        return withBody(encResponse, OK);
    }

    private byte[] additionalFrame(byte[] data) {
        if (awaitingEv2Step2) {
            return ev2Step2(data);
        }
        if (awaitingLegacyStep2) {
            return legacyStep2(data);
        }
        return status(ILLEGAL_COMMAND);
    }

    // ------------------------------------------------------------ application/key

    private byte[] createApplication(byte[] data) {
        byte[] plain = maybeVerifyCommandMac(0xCA, data, 5);
        int aid = (plain[0] & 0xFF) | ((plain[1] & 0xFF) << 8) | ((plain[2] & 0xFF) << 16);
        int numKeys = plain[4] & 0x0F;
        boolean existed = applications.containsKey(aid);
        if (!existed) {
            applications.put(aid, new Application(numKeys));
        }
        afterMacedCommand();
        return status(existed ? DUPLICATE : OK);
    }

    private byte[] changeKey(byte[] data) {
        requireSession();
        int keyNo = data[0] & 0xFF;
        byte[] cryptogram = Arrays.copyOfRange(data, 1, 33);
        byte[] providedMac = Arrays.copyOfRange(data, 33, 41);
        verifyCommandMac(0xC4, Arrays.copyOfRange(data, 0, 33), providedMac);

        byte[] iv = DesfireCrypto.commandIv(sessionKeyEnc, TI, commandCounter);
        byte[] plain = Aes.cbcDecrypt(sessionKeyEnc, iv, cryptogram);

        Application app = current();
        boolean changingAuthKey = keyNo == authenticatedKeyNo;
        byte[] newKey;
        if (changingAuthKey) {
            newKey = Arrays.copyOfRange(plain, 0, 16);
        } else {
            byte[] xor = Arrays.copyOfRange(plain, 0, 16);
            newKey = new byte[16];
            for (int i = 0; i < 16; i++) {
                newKey[i] = (byte) (xor[i] ^ app.keys[keyNo][i]);
            }
            int crc = DesfireCrypto.int32FromLittleEndian(plain, 16);
            if (crc != DesfireCrypto.crc32(newKey)) {
                return status(0x1E); // integrity error
            }
        }
        app.keys[keyNo] = newKey;
        commandCounter = (commandCounter + 1) & 0xFFFF;

        if (changingAuthKey) {
            endSession();
            return status(OK);
        }
        byte[] respMac = responseMac(null);
        return withBody(respMac, OK);
    }

    // ------------------------------------------------------------ value files

    private byte[] createValueFile(byte[] data) {
        requireSession();
        byte[] payload = Arrays.copyOfRange(data, 0, 17);
        byte[] providedMac = Arrays.copyOfRange(data, 17, 25);
        verifyCommandMac(0xCC, payload, providedMac);

        int fileNo = payload[0] & 0xFF;
        Application app = current();
        boolean existed = app.valueFiles.containsKey(fileNo);
        if (!existed) {
            ValueFile file = new ValueFile();
            file.lowerLimit = DesfireCrypto.int32FromLittleEndian(payload, 4);
            file.upperLimit = DesfireCrypto.int32FromLittleEndian(payload, 8);
            file.value = DesfireCrypto.int32FromLittleEndian(payload, 12);
            app.valueFiles.put(fileNo, file);
        }
        commandCounter = (commandCounter + 1) & 0xFFFF;
        return status(existed ? DUPLICATE : OK);
    }

    private byte[] getValue(byte[] data) {
        requireSession();
        int fileNo = data[0] & 0xFF;
        byte[] providedMac = Arrays.copyOfRange(data, 1, 9);
        verifyCommandMac(0x6C, new byte[] {(byte) fileNo}, providedMac);

        ValueFile file = current().valueFiles.get(fileNo);
        if (file == null) {
            return status(FILE_NOT_FOUND);
        }
        commandCounter = (commandCounter + 1) & 0xFFFF;

        byte[] padded = DesfireCrypto.padIso7816(DesfireCrypto.int32ToLittleEndian(file.value));
        byte[] iv = DesfireCrypto.responseIv(sessionKeyEnc, TI, commandCounter);
        byte[] ciphertext = Aes.cbcEncrypt(sessionKeyEnc, iv, padded);
        byte[] respMac = responseMac(ciphertext);
        return withBody(DesfireCrypto.concat(ciphertext, respMac), OK);
    }

    private byte[] valueOperation(int cmd, byte[] data) {
        requireSession();
        int fileNo = data[0] & 0xFF;
        byte[] ciphertext = Arrays.copyOfRange(data, 1, 17);
        byte[] providedMac = Arrays.copyOfRange(data, 17, 25);
        verifyCommandMac(cmd, Arrays.copyOfRange(data, 0, 17), providedMac);

        ValueFile file = current().valueFiles.get(fileNo);
        if (file == null) {
            return status(FILE_NOT_FOUND);
        }
        byte[] iv = DesfireCrypto.commandIv(sessionKeyEnc, TI, commandCounter);
        byte[] plain = Aes.cbcDecrypt(sessionKeyEnc, iv, ciphertext);
        int amount = DesfireCrypto.int32FromLittleEndian(plain, 0);

        int projected = file.staged + (cmd == 0xDC ? -amount : amount);
        if (file.value + projected < file.lowerLimit || file.value + projected > file.upperLimit) {
            commandCounter = (commandCounter + 1) & 0xFFFF; // card still consumed the command
            endSession();
            return status(BOUNDARY_ERROR);
        }
        file.staged = projected;
        commandCounter = (commandCounter + 1) & 0xFFFF;
        return withBody(responseMac(null), OK);
    }

    private byte[] commitTransaction(byte[] data) {
        requireSession();
        verifyCommandMac(0xC7, null, Arrays.copyOfRange(data, 0, 8));
        for (ValueFile file : current().valueFiles.values()) {
            file.value += file.staged;
            file.staged = 0;
        }
        commandCounter = (commandCounter + 1) & 0xFFFF;
        return withBody(responseMac(null), OK);
    }

    private byte[] abortTransaction(byte[] data) {
        requireSession();
        verifyCommandMac(0xA7, null, Arrays.copyOfRange(data, 0, 8));
        for (ValueFile file : current().valueFiles.values()) {
            file.staged = 0;
        }
        commandCounter = (commandCounter + 1) & 0xFFFF;
        return withBody(responseMac(null), OK);
    }

    // ------------------------------------------------------------ MAC helpers

    /**
     * Verifies the command MAC if a session is live and returns the plaintext
     * portion of {@code data}; returns {@code data} unchanged when no session is
     * live (a plain command).
     */
    private byte[] maybeVerifyCommandMac(int cmd, byte[] data, int plainLength) {
        if (!sessionActive) {
            return data;
        }
        byte[] plain = Arrays.copyOfRange(data, 0, plainLength);
        byte[] providedMac = Arrays.copyOfRange(data, plainLength, plainLength + 8);
        verifyCommandMac(cmd, plain, providedMac);
        return plain;
    }

    private void verifyCommandMac(int cmd, byte[] commandData, byte[] providedMac) {
        byte[] expected = truncatedMac(macInput(cmd, commandData));
        if (!Arrays.equals(expected, providedMac)) {
            endSession();
            throw new CardError(AUTHENTICATION_ERROR);
        }
    }

    private byte[] responseMac(byte[] responseData) {
        return truncatedMac(macInput(0x00, responseData));
    }

    private byte[] macInput(int leadingByte, byte[] data) {
        int length = data == null ? 0 : data.length;
        byte[] buffer = new byte[7 + length];
        buffer[0] = (byte) leadingByte;
        buffer[1] = (byte) (commandCounter & 0xFF);
        buffer[2] = (byte) ((commandCounter >>> 8) & 0xFF);
        System.arraycopy(TI, 0, buffer, 3, 4);
        if (length > 0) {
            System.arraycopy(data, 0, buffer, 7, length);
        }
        return buffer;
    }

    private byte[] truncatedMac(byte[] input) {
        return AesCmac.calculateTruncated(sessionKeyMac, input);
    }

    private void afterMacedCommand() {
        if (sessionActive) {
            commandCounter = (commandCounter + 1) & 0xFFFF;
        }
    }

    // ------------------------------------------------------------ plumbing

    private Application current() {
        return applications.get(selectedAid);
    }

    private void requireSession() {
        if (!sessionActive) {
            throw new CardError(PERMISSION_DENIED);
        }
    }

    private void endSession() {
        sessionActive = false;
    }

    private byte[] extractData(byte[] apdu) {
        if (apdu.length <= 5) {
            return new byte[0];
        }
        int lc = apdu[4] & 0xFF;
        return Arrays.copyOfRange(apdu, 5, 5 + lc);
    }

    private static byte[] status(int sw2) {
        return new byte[] {(byte) SW1, (byte) sw2};
    }

    private static byte[] withBody(byte[] body, int sw2) {
        byte[] out = new byte[body.length + 2];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = (byte) SW1;
        out[body.length + 1] = (byte) sw2;
        return out;
    }

    private static byte[] ramp(int start, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (start + i);
        }
        return out;
    }

    // ------------------------------------------------------------ unused link methods

    @Override
    public boolean isCardPresent() {
        return true;
    }

    @Override
    public ActivatedCard activate() throws NfcException {
        throw new UnsupportedOperationException("simulator is driven at the APDU layer");
    }

    @Override
    public void deselect() {
        endSession();
    }

    @Override
    public void resetField(long offMillis) {
        endSession();
    }

    @Override
    public FrameSize frameSize() {
        return FrameSize.DEFAULT;
    }

    @Override
    public void close() {
    }

    // ------------------------------------------------------------ inner state

    /** A card application: its AES keys and its value files. */
    public static final class Application {
        private final byte[][] keys;
        private final Map<Integer, ValueFile> valueFiles = new HashMap<>();

        Application(int numKeys) {
            keys = new byte[numKeys][];
            for (int i = 0; i < numKeys; i++) {
                keys[i] = new byte[16];
            }
        }

        /** Overrides a key, for tests that start from an already-personalised app. */
        public void setKey(int keyNo, byte[] key) {
            keys[keyNo] = key.clone();
        }

        /** Installs a value file directly, bypassing CreateValueFile. */
        public void putValueFile(int fileNo, int value, int lower, int upper) {
            ValueFile file = new ValueFile();
            file.value = value;
            file.lowerLimit = lower;
            file.upperLimit = upper;
            valueFiles.put(fileNo, file);
        }
    }

    private static final class ValueFile {
        int value;
        int staged;
        int lowerLimit;
        int upperLimit = Integer.MAX_VALUE;
    }

    /** A control-flow signal carrying the DESFire status byte to return. */
    private static final class CardError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int sw;

        CardError(int sw) {
            this.sw = sw;
        }
    }
}
