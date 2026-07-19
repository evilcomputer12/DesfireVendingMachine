package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.desfire.crypto.Aes;
import com.midnightbrewer.reference.desfire.crypto.AesCmac;
import com.midnightbrewer.reference.desfire.crypto.DesfireCrypto;
import com.midnightbrewer.reference.util.Hex;

/**
 * Live state of an {@code AuthenticateEV2First} session, and the secure
 * messaging built on it.
 *
 * <p>Port of the {@code DFSession} struct together with {@code _calc_cmd_mac},
 * {@code _verify_resp_mac}, {@code _calc_iv_cmd} and {@code _calc_iv_resp} from
 * {@code desfire_cmd.c}. In the C those are four free functions reaching into a
 * struct any caller can also write to; here the keys, the transaction
 * identifier and the counter are private, and the counter moves only through
 * {@link #incrementCounter}. That matters more than it looks -- see below.
 *
 * <h2>The command counter</h2>
 *
 * <p>Both the MAC and the IV take the counter as an input, so reader and card
 * must agree on its value at every step. The C increments it <em>after</em> the
 * card accepts a command and <em>before</em> the response MAC is verified,
 * which means:
 *
 * <ul>
 *   <li>the command MAC and the command IV use the pre-increment value;</li>
 *   <li>the response MAC and the response IV use the post-increment value.</li>
 * </ul>
 *
 * <p>That ordering is load-bearing. Getting it wrong produces a session that
 * authenticates perfectly and then fails its first MAC check with nothing to
 * point at, so it is stated here and honoured at exactly one call site per
 * command.
 *
 * <p>A session belongs to the application that was selected when it was
 * created. Selecting another application, or changing the key it authenticated
 * with, ends it -- hence {@link #invalidate()}.
 */
public final class DesfireSession {

    /** Length of the truncated MAC that accompanies a command or response. */
    public static final int MAC_LENGTH = AesCmac.TRUNCATED_LENGTH;

    private final byte[] sessionKeyEnc;
    private final byte[] sessionKeyMac;
    private final byte[] ti;
    private final int authenticatedKeyNumber;

    private int commandCounter;
    private boolean active = true;

    DesfireSession(byte[] sessionKeyEnc, byte[] sessionKeyMac, byte[] ti,
                   int authenticatedKeyNumber) {
        if (sessionKeyEnc.length != Aes.KEY_SIZE || sessionKeyMac.length != Aes.KEY_SIZE) {
            throw new IllegalArgumentException("session keys must be 16 bytes");
        }
        if (ti.length != DesfireCrypto.TI_LENGTH) {
            throw new IllegalArgumentException("TI must be 4 bytes");
        }
        this.sessionKeyEnc = sessionKeyEnc.clone();
        this.sessionKeyMac = sessionKeyMac.clone();
        this.ti = ti.clone();
        this.authenticatedKeyNumber = authenticatedKeyNumber;
    }

    /** The key number this session authenticated with. */
    public int authenticatedKeyNumber() {
        return authenticatedKeyNumber;
    }

    /** The card's 4-byte transaction identifier. */
    public byte[] transactionIdentifier() {
        return ti.clone();
    }

    /** Commands issued since authentication. Starts at zero. */
    public int commandCounter() {
        return commandCounter;
    }

    /** False once the session has been torn down and a re-authentication is required. */
    public boolean isActive() {
        return active;
    }

    /** Marks the session dead. Every further secure command must re-authenticate. */
    public void invalidate() {
        active = false;
    }

    /** Advances the command counter. See the class comment for when to call this. */
    public void incrementCounter() {
        commandCounter = (commandCounter + 1) & 0xFFFF;
    }

    // ---------------------------------------------------------------------- MACs

    /**
     * The 8-byte truncated CMAC that goes on the end of a command.
     *
     * <p>MAC input is {@code [cmd][ctrLo][ctrHi][TI0..TI3][commandData...]}.
     * Port of {@code _calc_cmd_mac}.
     */
    public byte[] commandMac(int command, byte[] commandData) {
        return AesCmac.calculateTruncated(sessionKeyMac, macInput(command, commandData));
    }

    /**
     * The 8-byte truncated CMAC a response is expected to carry.
     *
     * <p>MAC input is {@code [0x00][ctrLo][ctrHi][TI0..TI3][responseData...]}.
     * The leading byte is the return code, always {@code 0x00} here because a
     * response MAC is only ever checked after a successful status.
     * Port of {@code _verify_resp_mac}.
     */
    public byte[] responseMac(byte[] responseData) {
        return AesCmac.calculateTruncated(sessionKeyMac, macInput(0x00, responseData));
    }

    /** Whether {@code mac} is the correct response MAC over {@code responseData}. */
    public boolean verifyResponseMac(byte[] responseData, byte[] mac) {
        return DesfireCrypto.constantTimeEquals(responseMac(responseData), mac);
    }

    private byte[] macInput(int leadingByte, byte[] data) {
        int length = data == null ? 0 : data.length;
        byte[] buffer = new byte[7 + length];
        buffer[0] = (byte) leadingByte;
        buffer[1] = (byte) (commandCounter & 0xFF);
        buffer[2] = (byte) ((commandCounter >>> 8) & 0xFF);
        System.arraycopy(ti, 0, buffer, 3, DesfireCrypto.TI_LENGTH);
        if (length > 0) {
            System.arraycopy(data, 0, buffer, 7, length);
        }
        return buffer;
    }

    // --------------------------------------------------------------- encryption

    /** The IV for encrypting command data at the current counter. */
    public byte[] commandIv() {
        return DesfireCrypto.commandIv(sessionKeyEnc, ti, commandCounter);
    }

    /** The IV for decrypting response data at the current counter. */
    public byte[] responseIv() {
        return DesfireCrypto.responseIv(sessionKeyEnc, ti, commandCounter);
    }

    /**
     * CommMode.Full command data: ISO 7816-4 pad, then AES-CBC under the
     * command IV.
     */
    public byte[] encryptCommandData(byte[] plaintext) {
        return Aes.cbcEncrypt(sessionKeyEnc, commandIv(), DesfireCrypto.padIso7816(plaintext));
    }

    /**
     * AES-CBC of already block-aligned data under the command IV, with no
     * padding applied.
     *
     * <p>ChangeKey builds its 32-byte cryptogram (new key material plus a CRC
     * or version and its own {@code 0x80} padding) by hand, so it needs the
     * cipher without this class adding a second pad. Everything else uses
     * {@link #encryptCommandData}.
     */
    public byte[] encryptAligned(byte[] alignedPlaintext) {
        return Aes.cbcEncrypt(sessionKeyEnc, commandIv(), alignedPlaintext);
    }

    /**
     * CommMode.Full response data, decrypted under the response IV.
     *
     * <p>Padding is left in place: the caller knows how long its payload is,
     * and a value file's four bytes are easier to read off a fixed offset than
     * to recover from a padding scan.
     */
    public byte[] decryptResponseData(byte[] ciphertext) {
        return Aes.cbcDecrypt(sessionKeyEnc, responseIv(), ciphertext);
    }

    @Override
    public String toString() {
        return "DesfireSession[key=" + authenticatedKeyNumber
                + " TI=" + Hex.encode(ti)
                + " counter=" + commandCounter
                + (active ? "" : " INACTIVE") + "]";
    }
}
