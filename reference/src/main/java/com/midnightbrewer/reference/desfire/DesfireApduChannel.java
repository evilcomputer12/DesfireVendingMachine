package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.error.ProtocolException;
import com.midnightbrewer.reference.iso14443.Iso14443Transceiver;
import com.midnightbrewer.reference.util.Hex;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Speaks DESFire native commands wrapped in ISO 7816-4 APDUs, over any
 * {@link Iso14443Transceiver}.
 *
 * <p>The wrapping is the one {@code _build_apdu} in the plain-C DESFire library
 * uses, and the one the RC522 driver's response parsing assumes when it looks
 * for a {@code 0x91} status marker:
 *
 * <pre>
 *   90 &lt;cmd&gt; 00 00 [Lc &lt;data&gt;] 00
 * </pre>
 *
 * <p>Responses end with {@code 91 XX}, where {@code XX} is the DESFire status.
 * {@code 0xAF} means another frame follows and is fetched by sending the
 * {@code 0xAF} command with no data -- the continuation protocol
 * {@link #getVersion()} drives.
 *
 * <p>Depends on the interface, not on the RC522 implementation, so the same
 * command set works over any transport that can carry an APDU.
 */
public final class DesfireApduChannel {

    /** CLA for a wrapped DESFire native command. */
    private static final byte CLA_WRAPPED = (byte) 0x90;

    /** The continuation command: "send me the next frame". */
    public static final int CMD_ADDITIONAL_FRAME = 0xAF;

    /** GetVersion. */
    public static final int CMD_GET_VERSION = 0x60;

    /** Expected SW1 of a wrapped response. */
    private static final int SW1_WRAPPED = 0x91;

    /** Guard against a card that never stops asking for continuation frames. */
    private static final int MAX_CONTINUATION_FRAMES = 16;

    private final Iso14443Transceiver transceiver;

    public DesfireApduChannel(Iso14443Transceiver transceiver) {
        this.transceiver = Objects.requireNonNull(transceiver, "transceiver");
    }

    /**
     * Sends one native command and returns its body and status.
     *
     * @param command the DESFire command byte, e.g. {@code 0x60}
     * @param data    command data, or empty for none
     */
    public DesfireResponse send(int command, byte[] data) throws NfcException {
        byte[] apdu = wrap(command, data);
        byte[] response = transceiver.transceive(apdu);

        if (response.length < 2) {
            throw new ProtocolException(
                    "DESFire response too short for SW1 SW2: " + Hex.encode(response));
        }
        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;

        if (sw1 != SW1_WRAPPED) {
            throw new ProtocolException(
                    "bad DESFire framing: SW1=" + Hex.byteToString(sw1)
                            + " (expected 0x91) in " + Hex.encode(response));
        }
        byte[] body = new byte[response.length - 2];
        System.arraycopy(response, 0, body, 0, body.length);
        return new DesfireResponse(body, sw2);
    }

    /** {@code 90 cmd 00 00 [Lc data] 00}. */
    static byte[] wrap(int command, byte[] data) {
        int dataLength = data == null ? 0 : data.length;
        byte[] apdu = new byte[dataLength > 0 ? 6 + dataLength : 5];
        int i = 0;
        apdu[i++] = CLA_WRAPPED;
        apdu[i++] = (byte) command;
        apdu[i++] = 0x00;
        apdu[i++] = 0x00;
        if (dataLength > 0) {
            apdu[i++] = (byte) dataLength;
            System.arraycopy(data, 0, apdu, i, dataLength);
            i += dataLength;
        }
        apdu[i] = 0x00;   // Le
        return apdu;
    }

    /**
     * GetVersion, following the {@code 0xAF} continuation chain to the end.
     *
     * <p>A DESFire answers this in three frames -- hardware, software, then
     * production data -- each terminated with status {@code 0xAF} until the
     * last, which ends with {@code 0x00}. Every frame's body is concatenated and
     * handed to {@link DesfireVersion#parse}.
     *
     * <p>This is the ideal bring-up command: it needs no authentication, no
     * application selected and no keys, so it exercises the entire stack from
     * SPI to ISO-DEP chaining while being unable to modify the card.
     */
    public DesfireVersion getVersion() throws NfcException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();

        DesfireResponse response = send(CMD_GET_VERSION, new byte[0]);
        collected.writeBytes(response.body());

        int frames = 0;
        while (response.status() == DesfireStatus.ADDITIONAL_FRAME.code()) {
            if (++frames > MAX_CONTINUATION_FRAMES) {
                throw new ProtocolException(
                        "GetVersion did not terminate after " + MAX_CONTINUATION_FRAMES
                                + " continuation frames");
            }
            response = send(CMD_ADDITIONAL_FRAME, new byte[0]);
            collected.writeBytes(response.body());
        }

        if (response.status() != DesfireStatus.OPERATION_OK.code()) {
            throw new ProtocolException(
                    "GetVersion ended with " + DesfireStatus.describe(response.status()));
        }
        return DesfireVersion.parse(collected.toByteArray());
    }
}
