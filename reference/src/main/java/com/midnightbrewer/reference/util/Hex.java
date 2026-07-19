package com.midnightbrewer.reference.util;

/**
 * Hex formatting and parsing for frame dumps.
 *
 * <p>Every RF bug is diagnosed by comparing two byte strings, so this exists to
 * make those strings identical in shape to the ones {@code RC522.c} prints --
 * uppercase, space separated -- and therefore diffable against a working
 * firmware log.
 */
public final class Hex {

    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    /** Formats every byte as {@code "XX"}, separated by single spaces. */
    public static String encode(byte[] data) {
        return encode(data, 0, data == null ? 0 : data.length);
    }

    /** Formats {@code length} bytes starting at {@code offset}. */
    public static String encode(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            int b = data[offset + i] & 0xFF;
            out.append(DIGITS[b >>> 4]).append(DIGITS[b & 0x0F]);
        }
        return out.toString();
    }

    /** Parses a hex string, ignoring whitespace. Useful for test vectors. */
    public static byte[] decode(String text) {
        String compact = text.replaceAll("\\s", "");
        if ((compact.length() & 1) != 0) {
            throw new IllegalArgumentException("hex string has an odd number of digits");
        }
        byte[] out = new byte[compact.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Formats a single byte as {@code "0xXX"}. */
    public static String byteToString(int value) {
        int b = value & 0xFF;
        return "0x" + DIGITS[b >>> 4] + DIGITS[b & 0x0F];
    }
}
