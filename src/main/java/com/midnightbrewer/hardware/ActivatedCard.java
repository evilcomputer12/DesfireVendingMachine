package com.midnightbrewer.hardware;

/**
 * The result of a successful activation: what activate() hands back.
 *
 * STUDY THIS PATTERN -- it's a "value object", and it shows up everywhere in
 * good OOP. Three rules make it one:
 *   1. every field is 'final'          -- set once, in the constructor
 *   2. there are no setters             -- nobody can change it after
 *   3. arrays are copied in and out     -- so no one can reach in and mutate
 *      (a Java array is a reference; without the copy, a caller who kept the
 *       array they passed in could still change our internals behind our back)
 *
 * The payoff: once you hold an ActivatedCard, you can TRUST its data. It can't
 * shift under you. That's the same idea as your immutable Drink from the kiosk.
 */
public final class ActivatedCard {

    private final byte[] uid; // the full UID (4 or 7 bytes)
    private final int sak;    // Select Acknowledge byte
    private final byte[] ats; // Answer To Select (from RATS)

    public ActivatedCard(byte[] uid, int sak, byte[] ats) {
        this.uid = uid.clone(); // defensive copy IN
        this.sak = sak;
        this.ats = ats.clone();
    }

    public byte[] uid() {
        return uid.clone();     // defensive copy OUT
    }

    public int sak() {
        return sak;
    }

    public byte[] ats() {
        return ats.clone();
    }

    /** UID as a hex string, e.g. "04 76 18 62 7B 20 90". */
    public String uidHex() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uid.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", uid[i]));
        }
        return sb.toString();
    }
}
