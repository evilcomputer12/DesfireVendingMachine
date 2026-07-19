package com.midnightbrewer.model;

import java.util.Objects;

/**
 * One item on the menu.
 *
 * <p>Immutable on purpose: every field is {@code final} and there are no
 * setters, so a {@code Drink} handed to the payment layer cannot have its
 * price rewritten underneath you mid-transaction. This is encapsulation doing
 * real work rather than just being a textbook rule.
 *
 * <p>The price is stored as an {@code int} number of cents, never a
 * {@code double}. Two reasons, and the second one is the important one here:
 * <ol>
 *   <li>Binary floating point cannot represent 0.10 exactly, so money in
 *       {@code double} accumulates rounding error.</li>
 *   <li>A DESFire value file <em>is</em> a 4-byte signed integer. Storing
 *       cents means the UI, the debit command and the card all agree on the
 *       same units with no conversion step to get wrong.</li>
 * </ol>
 */
public final class Drink {

    private final String id;
    private final String displayName;
    private final int priceCents;

    /**
     * @param id          menu id; must match the artwork at
     *                    {@code /images/<id>.png}
     * @param displayName the name shown on the tile
     * @param priceCents  price in cents, must not be negative
     */
    public Drink(String id, String displayName, int priceCents) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        if (priceCents < 0) {
            throw new IllegalArgumentException("priceCents must be >= 0, got " + priceCents);
        }
        this.priceCents = priceCents;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getPriceCents() {
        return priceCents;
    }

    /** Classpath location of this drink's artwork. */
    public String getImagePath() {
        return "/images/" + id + ".png";
    }

    /** Price formatted for display, e.g. {@code "$2.50"}. */
    public String getFormattedPrice() {
        return formatCents(priceCents);
    }

    /** Shared money formatter so prices and balances always render alike. */
    public static String formatCents(int cents) {
        return String.format("$%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Drink)) {
            return false;
        }
        return id.equals(((Drink) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return displayName + " (" + getFormattedPrice() + ")";
    }
}
