package com.midnightbrewer.reference.picc;

import com.midnightbrewer.reference.util.Hex;

/**
 * The outcome of ISO 14443-3 activation: a card that has been anticollided and
 * selected, and is now the only one the reader will talk to.
 *
 * <p>{@code platform_activate_card} carries this state in three local variables
 * that go out of scope the moment it returns, so nothing downstream can ask
 * what it was talking to. Returning it as a value keeps the UID and SAK
 * available for the rest of the transaction -- which matters, because the SAK
 * is what says whether the card supports ISO 14443-4 at all.
 */
public final class SelectedCard {

    private final Uid uid;
    private final int sak;

    SelectedCard(Uid uid, int sak) {
        this.uid = uid;
        this.sak = sak & 0xFF;
    }

    /** The assembled 4- or 7-byte identifier. */
    public Uid uid() {
        return uid;
    }

    /** SAK, the Select Acknowledge byte, as returned by the final cascade level. */
    public int sak() {
        return sak;
    }

    /**
     * True if bit {@code 0x20} is set, meaning the card speaks ISO 14443-4 and
     * will answer RATS. A DESFire reports {@code 0x20}.
     */
    public boolean supportsIso14443Part4() {
        return (sak & 0x20) != 0;
    }

    /**
     * True if bit {@code 0x04} is still set, which would mean the UID was never
     * completed. After a successful activation this must be false.
     */
    public boolean isUidIncomplete() {
        return CascadeLevel.requiresNextLevel(sak);
    }

    @Override
    public String toString() {
        return "UID=" + uid + " SAK=" + Hex.byteToString(sak)
                + (supportsIso14443Part4() ? " (ISO 14443-4)" : " (not ISO 14443-4)");
    }
}
