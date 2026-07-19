package com.midnightbrewer.reference.desfire;

/**
 * The communication-mode byte used when creating a file, and the mode a command
 * runs in.
 *
 * <p>The value of the byte is what goes in the file's {@code commSettings}
 * field; the mode itself decides how the command that touches the file is
 * secured. This module always uses {@link #FULL} for the value file, mirroring
 * the C's use of CommMode.Full ({@code 0x03}) for its data file.
 */
public final class CommMode {

    private CommMode() {
    }

    /** Plain: no MAC, no encryption. */
    public static final int PLAIN = 0x00;

    /** MACed: cleartext payload with an appended CMAC. */
    public static final int MACED = 0x01;

    /** Full: AES-CBC encrypted payload with an appended CMAC. */
    public static final int FULL = 0x03;
}
