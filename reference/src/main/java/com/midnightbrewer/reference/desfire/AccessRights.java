package com.midnightbrewer.reference.desfire;

/**
 * A DESFire 16-bit file access-rights word.
 *
 * <p>Four nibbles, each a key number 0..13 or one of the two sentinels
 * {@link #FREE_ACCESS} / {@link #NO_ACCESS}:
 *
 * <pre>
 *   bits 15..12  read
 *   bits 11..8   write
 *   bits  7..4   read-write
 *   bits  3..0   change access rights
 * </pre>
 *
 * <p>For a value file the roles map onto the commands like this:
 * {@code read} is GetValue; {@code write} is Debit and LimitedCredit;
 * {@code readWrite} additionally allows Credit; {@code changeAccessRights}
 * governs ChangeFileSettings.
 *
 * <p>The word is transmitted little-endian, matching the {@code accessRights}
 * argument of {@code df_create_std_data_file} in the C. The
 * {@code DESFIRE_ACCESS_RIGHTS = 0x2220} constant in {@code desfiire.c} is
 * {@code singleKey(2)} with read-write reachable by key 0 -- reproduced by
 * {@link #forValueFile()}.
 */
public final class AccessRights {

    /** Nibble meaning "any key, no authentication needed". */
    public static final int FREE_ACCESS = 0x0E;

    /** Nibble meaning "never allowed". */
    public static final int NO_ACCESS = 0x0F;

    private final int read;
    private final int write;
    private final int readWrite;
    private final int changeAccessRights;

    public AccessRights(int read, int write, int readWrite, int changeAccessRights) {
        this.read = nibble(read, "read");
        this.write = nibble(write, "write");
        this.readWrite = nibble(readWrite, "readWrite");
        this.changeAccessRights = nibble(changeAccessRights, "changeAccessRights");
    }

    /** Access rights where every role is served by the same key. */
    public static AccessRights singleKey(int keyNumber) {
        return new AccessRights(keyNumber, keyNumber, keyNumber, keyNumber);
    }

    /**
     * The value-file rights the STM32 firmware uses, {@code 0x2220}.
     *
     * <p>read / write / change all require key 2 (the app user key), while
     * read-write is reachable by key 0. This is the layout the demo's value
     * file is created with.
     */
    public static AccessRights forValueFile() {
        return fromWord(0x2220);
    }

    /** Decodes a packed 16-bit word. */
    public static AccessRights fromWord(int word) {
        return new AccessRights(
                (word >>> 12) & 0x0F,
                (word >>> 8) & 0x0F,
                (word >>> 4) & 0x0F,
                word & 0x0F);
    }

    /** Packs the rights into the 16-bit word the card expects. */
    public int toWord() {
        return (read << 12) | (write << 8) | (readWrite << 4) | changeAccessRights;
    }

    /** The word as two little-endian bytes, ready for a command payload. */
    public byte[] toBytes() {
        int word = toWord();
        return new byte[] {(byte) (word & 0xFF), (byte) ((word >>> 8) & 0xFF)};
    }

    private static int nibble(int value, String name) {
        if (value < 0 || value > 0x0F) {
            throw new IllegalArgumentException(name + " must be a nibble 0..15, got " + value);
        }
        return value;
    }

    @Override
    public String toString() {
        return String.format("AccessRights(0x%04X)", toWord());
    }
}
