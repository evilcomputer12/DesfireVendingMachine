package com.midnightbrewer.card;

/**
 * DESFire command bytes and payload layouts.
 *
 * <p>Your C library ({@code desfire_cmd.c}) already covers application and
 * data-file commands. The block marked VALUE FILE below is the part it does
 * <strong>not</strong> have, and is what a payment system actually needs.
 *
 * <p>Reference: NXP MF3D(H)x3 datasheet and AN12343.
 */
public final class DesfireCommands {

    private DesfireCommands() {
    }

    // ── already implemented in your C library ────────────────────────
    public static final byte GET_VERSION          = (byte) 0x60;
    public static final byte SELECT_APPLICATION   = (byte) 0x5A;
    public static final byte CREATE_APPLICATION   = (byte) 0xCA;
    public static final byte DELETE_APPLICATION   = (byte) 0xDA;
    public static final byte FORMAT_PICC          = (byte) 0xFC;
    public static final byte AUTH_LEGACY          = (byte) 0x0A;
    public static final byte AUTH_ISO             = (byte) 0x1A;
    public static final byte AUTH_EV2_FIRST       = (byte) 0x71;
    public static final byte AUTH_EV2_NON_FIRST   = (byte) 0x77;
    public static final byte ADDITIONAL_FRAME     = (byte) 0xAF;
    public static final byte CREATE_STD_DATA_FILE = (byte) 0xCD;
    public static final byte CHANGE_FILE_SETTINGS = (byte) 0x5F;
    public static final byte WRITE_DATA           = (byte) 0x3D;
    public static final byte READ_DATA            = (byte) 0xBD;
    public static final byte CHANGE_KEY           = (byte) 0xC4;
    public static final byte GET_KEY_SETTINGS     = (byte) 0x45;
    public static final byte GET_KEY_VERSION      = (byte) 0x64;

    // ── VALUE FILE — you implement these ─────────────────────────────

    /**
     * CreateValueFile. Payload is 17 bytes:
     * <pre>
     *   FileNo               1
     *   CommSettings         1   (see COMM_* below)
     *   AccessRights         2   (little-endian, see ACCESS RIGHTS below)
     *   LowerLimit           4   (signed LE) minimum the value may reach
     *   UpperLimit           4   (signed LE) maximum the value may reach
     *   InitialValue         4   (signed LE)
     *   LimitedCreditEnabled 1   (0x00 off, 0x01 on)
     * </pre>
     */
    public static final byte CREATE_VALUE_FILE = (byte) 0xCC;

    /** GetValue. Payload: FileNo (1). Returns a 4-byte signed LE value. */
    public static final byte GET_VALUE = (byte) 0x6C;

    /** Credit — add value. Payload: FileNo (1) + amount (4, signed LE). */
    public static final byte CREDIT = (byte) 0x0C;

    /** Debit — subtract value. Payload: FileNo (1) + amount (4, signed LE). */
    public static final byte DEBIT = (byte) 0xDC;

    /**
     * LimitedCredit — refund up to the amount debited in this same session,
     * without needing the full Credit key. Payload: FileNo (1) + amount (4).
     */
    public static final byte LIMITED_CREDIT = (byte) 0x1C;

    /**
     * CommitTransaction — makes pending Credit/Debit changes durable.
     *
     * <p><strong>Until this succeeds, a Debit has not happened.</strong> The
     * card holds the change in a transaction buffer and discards it if power
     * is lost. Dispensing a drink before this returns OK means giving away
     * free coffee.
     */
    public static final byte COMMIT_TRANSACTION = (byte) 0xC7;

    /** AbortTransaction — explicitly discard pending changes. */
    public static final byte ABORT_TRANSACTION = (byte) 0xA7;

    /** GetFileSettings. Payload: FileNo (1). Useful for reading the limits back. */
    public static final byte GET_FILE_SETTINGS = (byte) 0xF5;

    /** GetFileIDs — no payload. Returns the file numbers in the selected app. */
    public static final byte GET_FILE_IDS = (byte) 0x6F;

    /** DeleteFile. Payload: FileNo (1). */
    public static final byte DELETE_FILE = (byte) 0xDF;

    /** GetCardUID — returns the real UID even when random-ID mode is on. */
    public static final byte GET_CARD_UID = (byte) 0x51;

    // ── communication modes (the CommSettings byte) ──────────────────

    /** Plain. Anyone sniffing the RF sees the amount. Never use for money. */
    public static final byte COMM_PLAIN = (byte) 0x00;

    /** MACed: readable but tamper-evident. */
    public static final byte COMM_MACED = (byte) 0x01;

    /** Fully enciphered. Use this for value files. */
    public static final byte COMM_FULL = (byte) 0x03;

    // ── status bytes ─────────────────────────────────────────────────
    public static final byte STATUS_OK               = (byte) 0x00;
    public static final byte STATUS_ADDITIONAL_FRAME = (byte) 0xAF;
    public static final byte STATUS_BOUNDARY_ERROR   = (byte) 0xBE;
    public static final byte STATUS_PERMISSION_DENIED = (byte) 0x9D;
    public static final byte STATUS_AUTH_ERROR       = (byte) 0xAE;

    /*
     * ── ACCESS RIGHTS ────────────────────────────────────────────────
     *
     * Two bytes, four 4-bit key numbers packed most-significant first:
     *
     *     bits 15..12   Read
     *     bits 11.. 8   Write
     *     bits  7.. 4   ReadWrite
     *     bits  3.. 0   Change
     *
     * 0x0..0xD are key numbers, 0xE means "free access, no auth", and 0xF
     * means "never permitted".
     *
     * Which right permits which value-file command is NOT obvious and is not
     * documented here on purpose — Credit in particular is commonly specified
     * as needing ReadWrite rather than Write, and several commands accept
     * more than one right. Look it up in the MF3D(H)x3 datasheet and confirm
     * it on a test card.
     *
     * The security design of this whole project lives in these four nibbles.
     * The kiosk sits in a public hallway and can be opened with a screwdriver,
     * so it must NOT hold the key that can add money. Give the kiosk a
     * debit-capable key and put Credit behind a different key number that only
     * the top-up station knows. Then the worst case for a stolen kiosk is an
     * attacker who can take money off cards, not mint it.
     *
     * Verify the exact command-to-right mapping in the MF3D(H)x3 datasheet
     * before you commit to a layout — get this wrong and you either cannot
     * debit at all, or you hand out a key that can print money.
     */
    public static short accessRights(int read, int write, int readWrite, int change) {
        return (short) (((read & 0xF) << 12) | ((write & 0xF) << 8)
                | ((readWrite & 0xF) << 4) | (change & 0xF));
    }

    /** Value files are 4-byte signed little-endian. */
    public static byte[] encodeValue(int value) {
        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    /** Inverse of {@link #encodeValue}. */
    public static int decodeValue(byte[] data, int offset) {
        if (data.length < offset + 4) {
            throw new IllegalArgumentException(
                    "need 4 bytes at offset " + offset + ", have " + data.length);
        }
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
