package com.midnightbrewer.reference.picc;

import com.midnightbrewer.reference.util.Hex;

/**
 * ATQA: the two bytes a card sends in reply to REQA or WUPA.
 *
 * <p>The C keeps this in a {@code uchar TagType[MAX_LEN]} and documents the
 * interesting values in a comment above {@code MFRC522_Request} --
 * {@code 0x4403} for a DESFire, {@code 0x0400} for a MIFARE Classic 1K, and so
 * on. Those live here as a method instead, so the knowledge is reachable from
 * code rather than only from a comment.
 *
 * <p>An ATQA is a hint, not an identification: several products share one, and
 * the authoritative answer comes from the SAK and the ATS. It is worth having
 * during bring-up because it is the first proof that a card is in the field at
 * all.
 */
public final class AnswerToRequest {

    private final int first;
    private final int second;

    AnswerToRequest(int first, int second) {
        this.first = first & 0xFF;
        this.second = second & 0xFF;
    }

    /** First ATQA byte. */
    public int firstByte() {
        return first;
    }

    /** Second ATQA byte. */
    public int secondByte() {
        return second;
    }

    /** Both bytes as one 16-bit value, in the order the C's comment lists them. */
    public int value() {
        return (first << 8) | second;
    }

    /**
     * True if the UID is announced as longer than four bytes.
     *
     * <p>ATQA bits 7..6 of the first byte carry the UID size: {@code 00} is
     * single, {@code 01} double. Only a hint -- the SAK decides -- but it lets
     * a poller predict a two-level cascade before it starts one.
     */
    public boolean suggestsDoubleSizeUid() {
        return (first & 0xC0) == 0x40;
    }

    /** A best-effort product name, from the C's comment on {@code MFRC522_Request}. */
    public String describe() {
        switch (value()) {
            case 0x4400: return "MIFARE Ultralight";
            case 0x0400: return "MIFARE Classic 1K (S50)";
            case 0x0200: return "MIFARE Classic 4K (S70)";
            case 0x0800: return "MIFARE Pro (X)";
            case 0x4403: return "MIFARE DESFire";
            default: return "unknown card type";
        }
    }

    @Override
    public String toString() {
        return Hex.encode(new byte[] {(byte) first, (byte) second}) + " (" + describe() + ")";
    }
}
