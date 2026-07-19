package com.midnightbrewer.reference.picc;

/**
 * Commands sent to the card, transcribed from the {@code PICC_*} defines in
 * {@code RC522.h}.
 *
 * <p>Only the first four are used by this module -- everything past
 * {@link #HALT} belongs to MIFARE Classic, which a DESFire does not speak --
 * but they are kept so the enum remains a complete reading of the header
 * rather than a filtered one, and so that a future MIFARE path has somewhere
 * obvious to start.
 */
public enum PiccCommand {

    /**
     * REQA. Invites cards in IDLE to move to READY. Sent as a 7-bit frame, so
     * {@code BitFramingReg} must be set to 7 first.
     */
    REQUEST_IDLE(0x26),

    /**
     * WUPA. Like REQA, but also wakes cards that have been HALTed. Also a
     * 7-bit frame.
     */
    WAKE_UP(0x52),

    /** Anticollision / SELECT, cascade level 1. */
    ANTICOLLISION_CASCADE_1(0x93),

    /** Anticollision / SELECT, cascade level 2. Needed for 7-byte UIDs. */
    ANTICOLLISION_CASCADE_2(0x95),

    /** Sends an ACTIVE card to HALT. */
    HALT(0x50),

    /** MIFARE Classic: authenticate with key A. */
    AUTHENTICATE_KEY_A(0x60),

    /** MIFARE Classic: authenticate with key B. */
    AUTHENTICATE_KEY_B(0x61),

    /** MIFARE Classic / Ultralight: read one 16-byte block. */
    READ(0x30),

    /** MIFARE Classic: write one 16-byte block. */
    WRITE(0xA0),

    /** MIFARE Classic value block: decrement into the internal data register. */
    DECREMENT(0xC0),

    /** MIFARE Classic value block: increment into the internal data register. */
    INCREMENT(0xC1),

    /** MIFARE Classic value block: read a block into the internal data register. */
    RESTORE(0xC2),

    /** MIFARE Classic value block: write the internal data register to a block. */
    TRANSFER(0xB0);

    private final int code;

    PiccCommand(int code) {
        this.code = code;
    }

    /** The command byte as it goes on the air. */
    public int code() {
        return code;
    }

    /** The command byte, ready to be placed in a frame buffer. */
    public byte toByte() {
        return (byte) code;
    }
}
