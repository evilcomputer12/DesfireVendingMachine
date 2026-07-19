package com.midnightbrewer.reference.picc;

import com.midnightbrewer.reference.util.Hex;

import java.util.Arrays;
import java.util.Objects;

/**
 * A card's unique identifier: four bytes, or seven for a DESFire.
 *
 * <p>Immutable, and it defends that: the array handed in is copied and the one
 * handed out is a fresh copy each time. A UID is an identity, and identities
 * that callers can reach in and edit are how one card's balance ends up on
 * another card.
 *
 * <p>Assembling the UID from the two cascade buffers is new here. The C never
 * does it -- {@code platform_activate_card} keeps {@code uid1} and {@code uid2}
 * as separate five-byte scratch arrays and prints them raw, cascade tag, BCC
 * and all -- because nothing in that firmware needed the identifier as a value.
 * Anything that keys a wallet off a card does.
 */
public final class Uid {

    private final byte[] bytes;

    private Uid(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * Builds a UID from the raw anticollision replies.
     *
     * <p>Each reply is five bytes: four payload bytes and a BCC. For a single-size
     * UID, level 1's four payload bytes <em>are</em> the UID. For a double-size
     * UID, level 1 returns {@code CT || uid0 uid1 uid2} and level 2 returns
     * {@code uid3 uid4 uid5 uid6}; the cascade tag and both BCCs are dropped.
     *
     * @param cascade1 the five-byte level 1 reply
     * @param cascade2 the five-byte level 2 reply, or {@code null} if the SAK
     *                 said the UID was already complete
     */
    public static Uid fromCascades(byte[] cascade1, byte[] cascade2) {
        Objects.requireNonNull(cascade1, "cascade1");
        if (cascade2 == null) {
            return new Uid(Arrays.copyOfRange(cascade1, 0, 4));
        }
        byte[] full = new byte[7];
        System.arraycopy(cascade1, 1, full, 0, 3);   // skip CT at index 0
        System.arraycopy(cascade2, 0, full, 3, 4);
        return new Uid(full);
    }

    /** Wraps an already-assembled UID. Copies the input. */
    public static Uid of(byte[] uidBytes) {
        return new Uid(uidBytes.clone());
    }

    /** A defensive copy of the identifier. */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /** 4 or 7. */
    public int length() {
        return bytes.length;
    }

    /** True for a 7-byte UID, which is what a DESFire has. */
    public boolean isDoubleSize() {
        return bytes.length == 7;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Uid uid && Arrays.equals(bytes, uid.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** Uppercase, space separated, matching the firmware's log format. */
    @Override
    public String toString() {
        return Hex.encode(bytes);
    }
}
