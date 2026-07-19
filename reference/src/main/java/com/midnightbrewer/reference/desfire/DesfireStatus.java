package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.util.Hex;

/**
 * The DESFire status byte, SW2 of an ISO-wrapped response.
 *
 * <p>Only the handful this bring-up path can produce are named. The rest are
 * reported by value, which is more useful than a wrong guess.
 */
public enum DesfireStatus {

    /** Command completed. */
    OPERATION_OK(0x00),

    /** More data follows; send {@code 0xAF} to fetch the next frame. */
    ADDITIONAL_FRAME(0xAF),

    /** No such key number on the card. */
    NO_SUCH_KEY(0x40),

    /** The command byte is not allowed in the current state. */
    ILLEGAL_COMMAND(0x1C),

    /**
     * A Credit/Debit would leave the value file's configured limits, or a
     * read/write ran past a file's end.
     */
    BOUNDARY_ERROR(0xBE),

    /** Not enough non-volatile memory to complete the command. */
    OUT_OF_MEMORY(0x0E),

    /** Length or parameter error in the command. */
    LENGTH_ERROR(0x7E),

    /** The command is not permitted in the current authentication state. */
    PERMISSION_DENIED(0x9D),

    /** A parameter of the command was invalid. */
    PARAMETER_ERROR(0x9E),

    /** The requested application does not exist on the card. */
    APPLICATION_NOT_FOUND(0xA0),

    /** Invalid file number, or the file does not exist. */
    FILE_NOT_FOUND(0xF0),

    /**
     * The thing being created already exists. Treated as success by
     * CreateApplication and CreateValueFile so provisioning is idempotent.
     */
    DUPLICATE_ERROR(0xDE),

    /** The previous command was aborted (e.g. a chained command left incomplete). */
    COMMAND_ABORTED(0xCA),

    /** Authentication required, or the wrong key was used. */
    AUTHENTICATION_ERROR(0xAE);

    private final int code;

    DesfireStatus(int code) {
        this.code = code;
    }

    /** The status byte value. */
    public int code() {
        return code;
    }

    /** The named status for a byte, or null if it is not one of the known ones. */
    public static DesfireStatus lookup(int code) {
        for (DesfireStatus status : values()) {
            if (status.code == (code & 0xFF)) {
                return status;
            }
        }
        return null;
    }

    /** A human-readable name for any status byte, named or not. */
    public static String describe(int code) {
        DesfireStatus status = lookup(code);
        return status != null
                ? status.name() + " (" + Hex.byteToString(code) + ")"
                : "unknown status " + Hex.byteToString(code);
    }
}
