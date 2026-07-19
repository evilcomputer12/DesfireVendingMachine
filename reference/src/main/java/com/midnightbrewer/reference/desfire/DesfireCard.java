package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.desfire.crypto.Aes;
import com.midnightbrewer.reference.desfire.crypto.DesfireCrypto;
import com.midnightbrewer.reference.desfire.crypto.LegacyDes;
import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.CardException;
import com.midnightbrewer.reference.error.DesfireStatusException;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.error.ProtocolException;

import java.util.Objects;

/**
 * The DESFire command set: authentication, application and key management, and
 * value-file operations, layered on a {@link DesfireApduChannel}.
 *
 * <p>Ports the command functions of {@code desfire_cmd.c} -- their APDU shapes,
 * their MAC and encryption handling, and the exact point at which the command
 * counter advances -- and adds the value-file commands the C library does not
 * have, following the CommMode.Full pattern the C uses for {@code WriteData}.
 * Where the C is ambiguous the independently cross-validated Dart port in
 * {@code flutter_topup/lib/desfire} was used as a second opinion, but the C is
 * authoritative and every method notes which function it came from.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>{@code FormatPICC} (0xFC) is not here and must never be. The C's
 * {@code df_setup_desfire} begins by formatting the card, which erases every
 * application on it; this class ports only steps 2 through 7 of that function
 * (see {@link #provisionValueWallet}). {@link DesfireCommand#FORMAT_PICC} exists
 * as a constant purely so tests can assert its byte never reaches the wire.
 *
 * <p>Not thread-safe. A card is a single serial conversation; one instance is
 * driven by one thread at a time.
 */
public final class DesfireCard {

    /** The PICC-level "root" application, selected to reach the card master key. */
    public static final int PICC_APPLICATION = 0x000000;

    /** Key version written to a freshly changed key, matching {@code df_setup_desfire}. */
    private static final int DEFAULT_KEY_VERSION = 0x01;

    private final DesfireApduChannel channel;
    private final RandomSource random;
    private final ProtocolTrace trace;

    private DesfireSession session;

    public DesfireCard(DesfireApduChannel channel) {
        this(channel, RandomSource.secure(), ProtocolTrace.none());
    }

    public DesfireCard(DesfireApduChannel channel, RandomSource random, ProtocolTrace trace) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.random = Objects.requireNonNull(random, "random");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    /** The live session, or null if not authenticated. Package-visible for tests. */
    DesfireSession session() {
        return session;
    }

    /** True while an EV2 session is usable. */
    public boolean isAuthenticated() {
        return session != null && session.isActive();
    }

    // =====================================================================
    // Application selection
    // =====================================================================

    /**
     * SelectApplication (0x5A). Port of {@code df_select_application}.
     *
     * <p>The AID is three bytes, little-endian. Selecting an application always
     * ends any session -- the C sets {@code active = false} unconditionally --
     * because session keys belong to the application that was selected when
     * they were derived.
     *
     * @throws DesfireStatusException with status {@code 0xA0} if the AID does
     *         not exist, which the wallet demo reads as "provision this card"
     */
    public void selectApplication(int aid) throws NfcException {
        byte[] data = {
                (byte) (aid & 0xFF),
                (byte) ((aid >>> 8) & 0xFF),
                (byte) ((aid >>> 16) & 0xFF),
        };
        DesfireResponse response = channel.send(DesfireCommand.SELECT_APPLICATION, data);
        session = null;
        requireOk(DesfireCommand.SELECT_APPLICATION, response);
        trace.log(() -> "SelectApplication " + String.format("0x%06X", aid) + " OK");
    }

    /**
     * GetKeySettings (0x45). Port of {@code df_get_key_settings}.
     *
     * @return the two key-setting bytes; byte 1's low nibble is the key count
     */
    public byte[] getKeySettings() throws NfcException {
        DesfireResponse response = channel.send(DesfireCommand.GET_KEY_SETTINGS, new byte[0]);
        requireOk(DesfireCommand.GET_KEY_SETTINGS, response);
        byte[] body = response.body();
        if (body.length < 2) {
            throw new ProtocolException("GetKeySettings returned " + body.length + " bytes, need 2");
        }
        return body;
    }

    // =====================================================================
    // Authentication
    // =====================================================================

    /**
     * AuthenticateEV2First (0x71) with an AES-128 key. Port of
     * {@code df_authenticate_ev2_first}.
     *
     * <p>The three-pass handshake: the card returns {@code enc(RndB)}, the
     * reader answers {@code enc(RndA || RndB')}, and the card returns
     * {@code enc(TI || RndA')}. Success installs a {@link DesfireSession} whose
     * counter starts at zero; from then on commands are MACed, and Full-mode
     * ones are encrypted, under the derived session keys.
     *
     * <p>Verifying the card's {@code RndA'} echo is what authenticates the
     * <em>card</em> to the reader -- without it, anything could answer with a
     * random blob and we would derive session keys that simply never match.
     */
    public DesfireSession authenticateEv2First(int keyNo, byte[] key) throws NfcException {
        session = null;
        if (key.length != Aes.KEY_SIZE) {
            throw new IllegalArgumentException("AES key must be 16 bytes");
        }

        byte[] step1Data = {(byte) keyNo, 0x00};
        DesfireResponse step1 = channel.send(DesfireCommand.AUTHENTICATE_EV2_FIRST, step1Data);
        if (step1.status() != DesfireStatus.ADDITIONAL_FRAME.code()) {
            throw new DesfireStatusException(DesfireCommand.AUTHENTICATE_EV2_FIRST, step1.status());
        }
        byte[] encRndB = step1.body();
        if (encRndB.length < Aes.BLOCK_SIZE) {
            throw new ProtocolException(
                    "AuthenticateEV2First step 1 gave " + encRndB.length + " bytes, need 16");
        }

        byte[] iv0 = new byte[Aes.BLOCK_SIZE];
        byte[] rndB = Aes.cbcDecrypt(key, iv0, slice(encRndB, 0, Aes.BLOCK_SIZE));
        byte[] rndA = random.nextBytes(Aes.BLOCK_SIZE);
        byte[] rndBrot = DesfireCrypto.rotateLeft(rndB);

        byte[] token = Aes.cbcEncrypt(key, iv0, DesfireCrypto.concat(rndA, rndBrot));
        DesfireResponse step2 = channel.send(DesfireCommand.ADDITIONAL_FRAME, token);
        if (step2.status() != DesfireStatus.OPERATION_OK.code()) {
            throw new DesfireStatusException(DesfireCommand.AUTHENTICATE_EV2_FIRST, step2.status());
        }
        byte[] step2Body = step2.body();
        if (step2Body.length < 32) {
            throw new ProtocolException(
                    "AuthenticateEV2First step 2 gave " + step2Body.length + " bytes, need 32");
        }

        byte[] decrypted = Aes.cbcDecrypt(key, iv0, slice(step2Body, 0, 32));
        byte[] rndArotExpected = DesfireCrypto.rotateLeft(rndA);
        byte[] rndArotReceived = slice(decrypted, 4, 16);
        if (!DesfireCrypto.constantTimeEquals(rndArotExpected, rndArotReceived)) {
            throw new ProtocolException(
                    "AuthenticateEV2First: card echoed the wrong RndA -- key mismatch or a replay");
        }

        byte[] ti = slice(decrypted, 0, DesfireCrypto.TI_LENGTH);
        byte[] sessKeyEnc = DesfireCrypto.deriveSessionKey(key, rndA, rndB, DesfireCrypto.LABEL_ENCRYPTION);
        byte[] sessKeyMac = DesfireCrypto.deriveSessionKey(key, rndA, rndB, DesfireCrypto.LABEL_MAC);
        session = new DesfireSession(sessKeyEnc, sessKeyMac, ti, keyNo);
        DesfireSession established = session;
        trace.log(() -> "AuthenticateEV2First key " + keyNo + " OK, " + established);
        return established;
    }

    /**
     * Authenticate (0x0A), the legacy D40 handshake, with a 16-byte 2K3DES key.
     * Port of {@code df_authenticate_legacy}.
     *
     * <p>Structurally not a cut-down EV2 handshake: 8-byte nonces, no
     * transaction identifier, no counter, and the reader uses the DES
     * <em>decrypt</em> primitive when sending. It therefore installs <em>no</em>
     * session -- commands afterwards go out in plain, which is exactly what
     * {@code CreateApplication} on a fresh card needs. Success is a {@code 0x00}
     * status; the C only ever logs the card's {@code RndA'} echo, and so does
     * this.
     */
    public void authenticateLegacy(int keyNo, byte[] key16) throws NfcException {
        session = null;
        if (key16.length != 16) {
            throw new IllegalArgumentException("2K3DES key must be 16 bytes");
        }

        DesfireResponse step1 = channel.send(DesfireCommand.AUTHENTICATE_LEGACY, new byte[] {(byte) keyNo});
        if (step1.status() != DesfireStatus.ADDITIONAL_FRAME.code()
                || step1.body().length != LegacyDes.BLOCK_SIZE) {
            throw new DesfireStatusException(DesfireCommand.AUTHENTICATE_LEGACY, step1.status());
        }

        byte[] encRndB = step1.body();
        byte[] zeroIv = new byte[LegacyDes.BLOCK_SIZE];
        byte[] rndB = LegacyDes.d40CbcReceive(key16, zeroIv, encRndB);
        byte[] rndA = random.nextBytes(LegacyDes.BLOCK_SIZE);
        byte[] rndBrot = DesfireCrypto.rotateLeft(rndB);

        byte[] plaintext = DesfireCrypto.concat(rndA, rndBrot);
        // D40 direction: correct for both the weak factory key and any other.
        byte[] token = LegacyDes.d40CbcSend(key16, encRndB, plaintext);

        DesfireResponse step2 = channel.send(DesfireCommand.ADDITIONAL_FRAME, token);
        if (step2.status() != DesfireStatus.OPERATION_OK.code()) {
            throw new DesfireStatusException(DesfireCommand.AUTHENTICATE_LEGACY, step2.status());
        }
        trace.log("Authenticate (0x0A) legacy OK -- card accepted");
    }

    /**
     * AuthenticateISO (0x1A) with a 24-byte 3K3DES key. Port of
     * {@code df_authenticate_iso}. Same shape as {@link #authenticateLegacy}
     * but ordinary CBC in both directions. Installs no session.
     */
    public void authenticateIso(int keyNo, byte[] key24) throws NfcException {
        session = null;
        if (key24.length != 24) {
            throw new IllegalArgumentException("3K3DES key must be 24 bytes");
        }

        DesfireResponse step1 = channel.send(DesfireCommand.AUTHENTICATE_ISO, new byte[] {(byte) keyNo});
        if (step1.status() != DesfireStatus.ADDITIONAL_FRAME.code()
                || step1.body().length != LegacyDes.BLOCK_SIZE) {
            throw new DesfireStatusException(DesfireCommand.AUTHENTICATE_ISO, step1.status());
        }

        byte[] encRndB = step1.body();
        byte[] zeroIv = new byte[LegacyDes.BLOCK_SIZE];
        byte[] rndB = LegacyDes.tripleDes3kCbcDecrypt(key24, zeroIv, encRndB);
        byte[] rndA = random.nextBytes(LegacyDes.BLOCK_SIZE);
        byte[] rndBrot = DesfireCrypto.rotateLeft(rndB);

        byte[] token = LegacyDes.tripleDes3kCbcEncrypt(key24, encRndB, DesfireCrypto.concat(rndA, rndBrot));
        DesfireResponse step2 = channel.send(DesfireCommand.ADDITIONAL_FRAME, token);
        if (step2.status() != DesfireStatus.OPERATION_OK.code()) {
            throw new DesfireStatusException(DesfireCommand.AUTHENTICATE_ISO, step2.status());
        }
        trace.log("AuthenticateISO (0x1A) OK -- card accepted");
    }

    /**
     * Authenticates at PICC level with a factory master key, trying each type a
     * card generation might present it as. Port of {@code _auth_picc_factory}.
     *
     * <p>The factory PICC master key is sixteen zero bytes on every DESFire, but
     * its declared <em>type</em> is not fixed: older cards want the D40
     * handshake (0x0A), some are shipped or left in 3K3DES (0x1A, key
     * {@code key16 || key16[0..8]}), and a card that has been AES-personalised
     * wants EV2First (0x71). The C tries all three in that order; so does this.
     * Only the EV2 path leaves a session behind.
     */
    public void authenticatePiccFactory(byte[] key16) throws NfcException {
        if (key16.length != 16) {
            throw new IllegalArgumentException("PICC master key must be 16 bytes");
        }
        try {
            authenticateLegacy(0, key16);
            return;
        } catch (CardException legacyFailure) {
            trace.log("PICC factory auth: legacy (0x0A) refused, trying ISO (0x1A)");
        }
        try {
            authenticateIso(0, LegacyDes.to3k3desKey(key16));
            return;
        } catch (CardException isoFailure) {
            trace.log("PICC factory auth: ISO (0x1A) refused, trying EV2First (0x71)");
        }
        authenticateEv2First(0, key16);
    }

    // =====================================================================
    // Application and key management
    // =====================================================================

    /**
     * CreateApplication (0xCA). Port of {@code df_create_application}.
     *
     * <p>Command data is the C's five bytes:
     * {@code [aidLo, aidMid, aidHi, 0xEF, 0x80 | numKeys]}. {@code 0xEF} is the
     * key-settings byte; {@code 0x80} in the last byte selects AES application
     * keys, which is what lets everything afterwards use EV2First. When a
     * session is live an 8-byte command MAC is appended; on a fresh card there
     * is none, because CreateApplication runs straight after a legacy PICC
     * authentication that installed no session, so the command goes out plain.
     *
     * <p>A {@code 0xDE} (DUPLICATE_ERROR) status is treated as success and
     * reported through the return value -- this is what makes provisioning
     * idempotent.
     *
     * @return true if the application already existed
     */
    public boolean createApplication(int aid, int numKeys) throws NfcException {
        if (numKeys < 1 || numKeys > 14) {
            throw new IllegalArgumentException("an application needs 1..14 keys, not " + numKeys);
        }
        byte[] plain = {
                (byte) (aid & 0xFF),
                (byte) ((aid >>> 8) & 0xFF),
                (byte) ((aid >>> 16) & 0xFF),
                (byte) 0xEF,
                (byte) (0x80 | (numKeys & 0x0F)),
        };

        boolean live = isAuthenticated();
        byte[] payload = live
                ? DesfireCrypto.concat(plain, session.commandMac(DesfireCommand.CREATE_APPLICATION, plain))
                : plain;

        DesfireResponse response = channel.send(DesfireCommand.CREATE_APPLICATION, payload);
        int status = response.status();
        if (status != DesfireStatus.OPERATION_OK.code()
                && status != DesfireStatus.DUPLICATE_ERROR.code()) {
            if (live) {
                session.invalidate();
            }
            throw new DesfireStatusException(DesfireCommand.CREATE_APPLICATION, status);
        }
        if (live) {
            session.incrementCounter();
        }
        boolean existed = status == DesfireStatus.DUPLICATE_ERROR.code();
        trace.log(() -> existed
                ? "CreateApplication: AID already present, left untouched"
                : "CreateApplication: created " + String.format("0x%06X", aid) + " with " + numKeys + " key(s)");
        return existed;
    }

    /**
     * ChangeKey (0xC4) in an EV2 session. Port of {@code df_change_key}.
     *
     * <p>The cryptogram depends on whether the key being replaced is the one the
     * session authenticated with:
     *
     * <ul>
     *   <li><b>Same key</b> -- {@code newKey || keyVersion}, padded to 32
     *       bytes. The card already holds the old key, so no proof of
     *       possession is required.</li>
     *   <li><b>Different key</b> -- {@code (newKey XOR oldKey) || CRC32(newKey)
     *       || keyVersion}, padded. The XOR means the card can only recover
     *       {@code newKey} if the caller genuinely knew {@code oldKey}, and the
     *       CRC lets it verify that it did. Byte order follows the C exactly.</li>
     * </ul>
     *
     * <p>Changing the authenticated key makes the card tear the session down, so
     * this clears the local session too. The caller must re-select and
     * re-authenticate afterwards -- which is why {@link #provisionValueWallet}
     * re-authenticates after every key change.
     */
    public void changeKey(int keyNo, byte[] oldKey, byte[] newKey, int keyVersion) throws NfcException {
        DesfireSession current = requireSession();
        if (oldKey.length != Aes.KEY_SIZE || newKey.length != Aes.KEY_SIZE) {
            throw new IllegalArgumentException("AES keys must be 16 bytes");
        }
        boolean changingAuthKey = keyNo == current.authenticatedKeyNumber();

        byte[] plain = new byte[32];
        if (changingAuthKey) {
            System.arraycopy(newKey, 0, plain, 0, 16);
            plain[16] = (byte) keyVersion;
            plain[17] = (byte) 0x80;
        } else {
            for (int i = 0; i < 16; i++) {
                plain[i] = (byte) (newKey[i] ^ oldKey[i]);
            }
            byte[] crc = DesfireCrypto.crc32ToBytes(DesfireCrypto.crc32(newKey));
            System.arraycopy(crc, 0, plain, 16, 4);
            plain[20] = (byte) keyVersion;
            plain[21] = (byte) 0x80;
        }

        byte[] cryptogram = current.encryptAligned(plain);
        byte[] cmdData = DesfireCrypto.concat(new byte[] {(byte) keyNo}, cryptogram);
        byte[] mac = current.commandMac(DesfireCommand.CHANGE_KEY, cmdData);

        DesfireResponse response = channel.send(DesfireCommand.CHANGE_KEY, DesfireCrypto.concat(cmdData, mac));
        if (response.status() != DesfireStatus.OPERATION_OK.code()) {
            current.invalidate();
            throw new DesfireStatusException(DesfireCommand.CHANGE_KEY, response.status());
        }
        current.incrementCounter();

        if (changingAuthKey) {
            current.invalidate();
            trace.log("ChangeKey " + keyNo + " (authenticated key): session torn down, re-auth required");
        } else {
            byte[] body = response.body();
            if (body.length >= DesfireSession.MAC_LENGTH
                    && !current.verifyResponseMac(null, slice(body, body.length - DesfireSession.MAC_LENGTH,
                            DesfireSession.MAC_LENGTH))) {
                throw new ProtocolException("ChangeKey response MAC failed");
            }
            trace.log("ChangeKey " + keyNo + " OK");
        }
    }

    // =====================================================================
    // Value files
    // =====================================================================

    /**
     * CreateValueFile (0xCC). Requires a session authenticated with a key
     * allowed to create files (the application master key).
     *
     * <p>Not in the C library. It follows the same shape as the C's
     * {@code df_create_std_data_file}: the 17-byte plaintext settings with an
     * 8-byte command MAC appended -- the creation parameters are MACed, not
     * encrypted, even though they name CommMode.Full for later access. A
     * {@code 0xDE} status is treated as success so the demo can be re-run.
     *
     * @return true if the file already existed
     */
    public boolean createValueFile(ValueFileSettings settings) throws NfcException {
        DesfireSession current = requireSession();
        byte[] plain = settings.toBytes();
        byte[] mac = current.commandMac(DesfireCommand.CREATE_VALUE_FILE, plain);

        DesfireResponse response = channel.send(DesfireCommand.CREATE_VALUE_FILE, DesfireCrypto.concat(plain, mac));
        int status = response.status();
        if (status != DesfireStatus.OPERATION_OK.code()
                && status != DesfireStatus.DUPLICATE_ERROR.code()) {
            current.invalidate();
            throw new DesfireStatusException(DesfireCommand.CREATE_VALUE_FILE, status);
        }
        current.incrementCounter();
        boolean existed = status == DesfireStatus.DUPLICATE_ERROR.code();
        trace.log(() -> existed
                ? "CreateValueFile: file already present, left untouched"
                : "CreateValueFile: created file " + settings.fileNo());
        return existed;
    }

    /**
     * GetValue (0x6C) in CommMode.Full. Reads the value file's balance.
     *
     * <p>Command data is the plaintext file number plus the command MAC. The
     * response is one AES-CBC block holding the 4-byte little-endian value and
     * its padding, then the 8-byte response MAC. The counter is advanced before
     * the response MAC and IV are computed, so both use the post-increment
     * value -- the ordering the C fixes and this relies on.
     *
     * @return the stored value (cents, in this project's convention)
     */
    public int getValue(int fileNo) throws NfcException {
        DesfireSession current = requireSession();
        byte[] header = {(byte) fileNo};
        byte[] mac = current.commandMac(DesfireCommand.GET_VALUE, header);

        DesfireResponse response = channel.send(DesfireCommand.GET_VALUE, DesfireCrypto.concat(header, mac));
        requireOk(DesfireCommand.GET_VALUE, response, current);
        current.incrementCounter();

        byte[] body = response.body();
        if (body.length < Aes.BLOCK_SIZE + DesfireSession.MAC_LENGTH) {
            throw new ProtocolException("GetValue returned " + body.length + " bytes, need at least 24");
        }
        byte[] encrypted = slice(body, 0, Aes.BLOCK_SIZE);
        byte[] responseMac = slice(body, Aes.BLOCK_SIZE, DesfireSession.MAC_LENGTH);
        if (!current.verifyResponseMac(encrypted, responseMac)) {
            throw new ProtocolException("GetValue response MAC failed -- refusing to trust the balance");
        }
        byte[] plain = current.decryptResponseData(encrypted);
        return DesfireCrypto.int32FromLittleEndian(plain, 0);
    }

    /**
     * Credit (0x0C) in CommMode.Full. Stages a positive change.
     *
     * <p><b>Only stages it.</b> The balance does not move until
     * {@link #commitTransaction()} succeeds.
     */
    public void credit(int fileNo, int amount) throws NfcException {
        if (amount <= 0) {
            throw new IllegalArgumentException("credit amount must be positive");
        }
        valueOperation(DesfireCommand.CREDIT, fileNo, amount);
    }

    /**
     * Debit (0xDC) in CommMode.Full. Stages a negative change.
     *
     * <p><b>Only stages it.</b> Not permanent until {@link #commitTransaction()}
     * succeeds.
     */
    public void debit(int fileNo, int amount) throws NfcException {
        if (amount <= 0) {
            throw new IllegalArgumentException("debit amount must be positive");
        }
        valueOperation(DesfireCommand.DEBIT, fileNo, amount);
    }

    /**
     * Shared wire format for Credit and Debit, following the C's WriteData
     * pattern: a plaintext header (the file number, kept in the clear like
     * WriteData's header), then the 4-byte amount ISO 7816-4 padded to 16 bytes
     * and AES-CBC encrypted under the command IV, then a command MAC over
     * {@code [cmd][ctr][TI][fileNo][ciphertext]}.
     */
    private void valueOperation(int command, int fileNo, int amount) throws NfcException {
        DesfireSession current = requireSession();
        byte[] header = {(byte) fileNo};
        byte[] ciphertext = current.encryptCommandData(DesfireCrypto.int32ToLittleEndian(amount));
        byte[] macInput = DesfireCrypto.concat(header, ciphertext);
        byte[] mac = current.commandMac(command, macInput);

        DesfireResponse response = channel.send(command, DesfireCrypto.concat(macInput, mac));
        if (response.status() != DesfireStatus.OPERATION_OK.code()) {
            current.invalidate();
            throw new DesfireStatusException(command, response.status());
        }
        current.incrementCounter();

        byte[] body = response.body();
        if (body.length >= DesfireSession.MAC_LENGTH
                && !current.verifyResponseMac(null, slice(body, body.length - DesfireSession.MAC_LENGTH,
                        DesfireSession.MAC_LENGTH))) {
            throw new ProtocolException("value command response MAC failed");
        }
    }

    /**
     * CommitTransaction (0xC7) in CommMode.MAC. Makes a staged Credit/Debit
     * permanent.
     *
     * <p>This is the command whose success actually moves the balance. If the
     * card leaves the field before it returns {@code 0x00}, the card discards
     * the staged change and the balance is unchanged -- so a debit must only be
     * reported to a user after this returns.
     */
    public void commitTransaction() throws NfcException {
        DesfireSession current = requireSession();
        byte[] mac = current.commandMac(DesfireCommand.COMMIT_TRANSACTION, null);

        DesfireResponse response = channel.send(DesfireCommand.COMMIT_TRANSACTION, mac);
        if (response.status() != DesfireStatus.OPERATION_OK.code()) {
            current.invalidate();
            throw new DesfireStatusException(DesfireCommand.COMMIT_TRANSACTION, response.status());
        }
        current.incrementCounter();

        byte[] body = response.body();
        byte[] payload = body.length > DesfireSession.MAC_LENGTH
                ? slice(body, 0, body.length - DesfireSession.MAC_LENGTH)
                : new byte[0];
        if (body.length >= DesfireSession.MAC_LENGTH
                && !current.verifyResponseMac(payload.length == 0 ? null : payload,
                        slice(body, body.length - DesfireSession.MAC_LENGTH, DesfireSession.MAC_LENGTH))) {
            throw new ProtocolException(
                    "CommitTransaction response MAC failed -- the commit is unconfirmed");
        }
        trace.log("CommitTransaction OK");
    }

    /**
     * AbortTransaction (0xA7) in CommMode.MAC. Discards any staged value change.
     *
     * <p>Best effort: the card discards the transaction anyway once it leaves
     * the field, so a failure here is logged, not fatal to correctness.
     */
    public void abortTransaction() throws NfcException {
        DesfireSession current = requireSession();
        byte[] mac = current.commandMac(DesfireCommand.ABORT_TRANSACTION, null);

        DesfireResponse response = channel.send(DesfireCommand.ABORT_TRANSACTION, mac);
        if (response.status() != DesfireStatus.OPERATION_OK.code()) {
            current.invalidate();
            throw new DesfireStatusException(DesfireCommand.ABORT_TRANSACTION, response.status());
        }
        current.incrementCounter();

        byte[] body = response.body();
        if (body.length >= DesfireSession.MAC_LENGTH
                && !current.verifyResponseMac(null, slice(body, body.length - DesfireSession.MAC_LENGTH,
                        DesfireSession.MAC_LENGTH))) {
            throw new ProtocolException("AbortTransaction response MAC failed");
        }
        trace.log("AbortTransaction OK");
    }

    // =====================================================================
    // Provisioning
    // =====================================================================

    /**
     * Provisions the value-file wallet: steps 2 through 7 of
     * {@code df_setup_desfire}, with a value file in place of its data file.
     *
     * <p><b>Step 1 of the C function is deliberately omitted.</b> That step is
     * {@code df_full_format}, which authenticates at PICC level and sends
     * {@code FormatPICC} -- erasing every application on the card, not just this
     * one. This method never formats. It is strictly additive: it creates one
     * application alongside whatever else is on the card and touches nothing
     * else, and there is no code path here that can emit {@code 0xFC}.
     *
     * <p>The steps, mirroring the C:
     * <ol>
     *   <li>select PICC root and authenticate the (factory) PICC master key;</li>
     *   <li>CreateApplication (idempotent: {@code 0xDE} counts as success);</li>
     *   <li>select the new app, authenticate the default key 0;</li>
     *   <li>if a separate user key slot is configured, change it and re-auth;</li>
     *   <li>change the application master key and re-auth with the new one;</li>
     *   <li>CreateValueFile with the requested initial balance.</li>
     * </ol>
     *
     * <p>The re-select / re-authenticate after each key change is not
     * defensive: ChangeKey on the authenticated key makes the card drop the
     * session, so the next step needs a fresh one.
     */
    public void provisionValueWallet(WalletProfile profile) throws NfcException {
        byte[] zeros = new byte[16];
        int numKeys = profile.userKeyNo() == 0 ? 1 : profile.userKeyNo() + 1;

        // Step 2 (C step 2): reach the card master key and create the app.
        trace.log("Provision: select PICC root and authenticate factory master key");
        selectApplication(PICC_APPLICATION);
        authenticatePiccFactory(profile.piccMasterKey());
        createApplication(profile.aid(), numKeys);

        // Step 3: select the new app and authenticate its default master key.
        trace.log("Provision: select app and authenticate default key 0");
        selectApplication(profile.aid());
        authenticateEv2First(0, zeros);

        // Step 4: personalise the user key, if a separate slot is used.
        if (profile.userKeyNo() > 0) {
            trace.log("Provision: change user key " + profile.userKeyNo());
            authenticateEv2First(profile.userKeyNo(), zeros);
            changeKey(profile.userKeyNo(), zeros, profile.appUserKey(), DEFAULT_KEY_VERSION);
            selectApplication(profile.aid());
            authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        }

        // Step 5: change the application master key.
        trace.log("Provision: change application master key 0");
        selectApplication(profile.aid());
        authenticateEv2First(0, zeros);
        changeKey(0, zeros, profile.appMasterKey(), DEFAULT_KEY_VERSION);

        // Step 6: re-authenticate with the new master key before creating the file.
        selectApplication(profile.aid());
        authenticateEv2First(0, profile.appMasterKey());

        // Step 7: create the value file (the demo's substitute for the C's data file).
        trace.log("Provision: create value file " + profile.fileNo());
        createValueFile(ValueFileSettings.builder(profile.fileNo())
                .accessRights(profile.accessRights())
                .lowerLimit(profile.lowerLimit())
                .upperLimit(profile.upperLimit())
                .initialValue(profile.initialBalance())
                .build());
        trace.log("Provision: done");
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private DesfireSession requireSession() throws NfcException {
        if (!isAuthenticated()) {
            throw new ProtocolException("no active EV2 session -- authenticate first");
        }
        return session;
    }

    private void requireOk(int command, DesfireResponse response) throws DesfireStatusException {
        if (response.status() != DesfireStatus.OPERATION_OK.code()) {
            throw new DesfireStatusException(command, response.status());
        }
    }

    private void requireOk(int command, DesfireResponse response, DesfireSession current)
            throws DesfireStatusException {
        if (response.status() != DesfireStatus.OPERATION_OK.code()) {
            current.invalidate();
            throw new DesfireStatusException(command, response.status());
        }
    }

    private static byte[] slice(byte[] source, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, offset, out, 0, length);
        return out;
    }
}
