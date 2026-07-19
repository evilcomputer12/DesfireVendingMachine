package com.midnightbrewer.reference.desfire;

/**
 * The DESFire native command bytes this module uses.
 *
 * <p>Values are taken verbatim from the {@code #define CMD_*} block at the top
 * of {@code desfire_cmd.c}, plus the value-file commands, which the C library
 * does not implement -- their codes come from the NXP data sheet and are
 * cross-checked against the Dart port's {@code DesfireCommand}.
 *
 * <p>{@link #FORMAT_PICC} is present as a named constant for exactly one
 * reason: so tests can assert its byte never appears on the wire. It has no
 * method. Formatting erases the whole card, and this module must never send it.
 */
public final class DesfireCommand {

    private DesfireCommand() {
    }

    // --- session / application management ---

    /** GetVersion. */
    public static final int GET_VERSION = 0x60;

    /** SelectApplication (3-byte AID). */
    public static final int SELECT_APPLICATION = 0x5A;

    /** CreateApplication. */
    public static final int CREATE_APPLICATION = 0xCA;

    /** GetKeySettings. */
    public static final int GET_KEY_SETTINGS = 0x45;

    /** The continuation command: "send me the next frame". */
    public static final int ADDITIONAL_FRAME = 0xAF;

    // --- authentication ---

    /** Authenticate (legacy D40, DES / 2K3DES). */
    public static final int AUTHENTICATE_LEGACY = 0x0A;

    /** AuthenticateISO (3K3DES). */
    public static final int AUTHENTICATE_ISO = 0x1A;

    /** AuthenticateEV2First (AES-128). */
    public static final int AUTHENTICATE_EV2_FIRST = 0x71;

    // --- keys ---

    /** ChangeKey. */
    public static final int CHANGE_KEY = 0xC4;

    // --- value files ---

    /** CreateValueFile. */
    public static final int CREATE_VALUE_FILE = 0xCC;

    /** GetValue. */
    public static final int GET_VALUE = 0x6C;

    /** Credit. */
    public static final int CREDIT = 0x0C;

    /** Debit. */
    public static final int DEBIT = 0xDC;

    /** LimitedCredit. */
    public static final int LIMITED_CREDIT = 0x1C;

    /** CommitTransaction -- makes a staged Credit/Debit permanent. */
    public static final int COMMIT_TRANSACTION = 0xC7;

    /** AbortTransaction -- discards a staged Credit/Debit. */
    public static final int ABORT_TRANSACTION = 0xA7;

    // --- forbidden ---

    /**
     * FormatPICC. Erases every application and file on the card.
     *
     * <p>Named so it can be forbidden, never sent. There is no code path in
     * this module that issues it, and a test asserts as much.
     */
    public static final int FORMAT_PICC = 0xFC;
}
