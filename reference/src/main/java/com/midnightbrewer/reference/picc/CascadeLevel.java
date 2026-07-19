package com.midnightbrewer.reference.picc;

/**
 * The two anticollision cascade levels this driver walks.
 *
 * <p>ISO 14443-3 anticollision moves four UID bytes at a time. A card with a
 * 4-byte UID is done after level 1. A DESFire has a 7-byte UID, so level 1
 * returns a cascade tag ({@value #CASCADE_TAG}) followed by the first three UID
 * bytes, and the remaining four arrive at level 2.
 *
 * <p>The card announces which case applies in the SAK returned by SELECT: bit
 * {@code 0x04} set means "UID not complete, run the next cascade level". That
 * is the branch {@code platform_activate_card} takes, and the reason this port
 * cannot stop at one level.
 *
 * <p>The same command byte serves anticollision and SELECT at a given level;
 * they are told apart by the NVB byte that follows -- {@code 0x20} for
 * anticollision, {@code 0x70} for SELECT.
 */
public enum CascadeLevel {

    /** {@code PICC_ANTICOLL} / {@code PICC_SElECTTAG}. */
    LEVEL_1(PiccCommand.ANTICOLLISION_CASCADE_1),

    /** {@code PICC_ANTICOLL2} / {@code PICC_SElECTTAG2}. */
    LEVEL_2(PiccCommand.ANTICOLLISION_CASCADE_2);

    /**
     * CT, the cascade tag. When a UID is longer than four bytes, level 1
     * returns this in its first byte to say so.
     */
    public static final int CASCADE_TAG = 0x88;

    /** NVB for anticollision: no UID bits known yet, so send the two-byte header only. */
    static final byte NVB_ANTICOLLISION = 0x20;

    /** NVB for SELECT: the full 40 bits of UID plus BCC follow. */
    static final byte NVB_SELECT = 0x70;

    /** SAK bit meaning "UID incomplete, continue with the next cascade level". */
    public static final int SAK_CASCADE_BIT = 0x04;

    private final PiccCommand command;

    CascadeLevel(PiccCommand command) {
        this.command = command;
    }

    /** The SEL byte for this level. */
    public PiccCommand command() {
        return command;
    }

    /** True if this SAK says another cascade level is required. */
    public static boolean requiresNextLevel(int sak) {
        return (sak & SAK_CASCADE_BIT) != 0;
    }
}
