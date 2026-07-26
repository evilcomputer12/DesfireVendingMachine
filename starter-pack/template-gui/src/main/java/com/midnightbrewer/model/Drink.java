package com.midnightbrewer.model;

/**
 * One item on the menu.
 *
 * A VALUE OBJECT: every field is final, there are no setters, and it holds data
 * rather than behaviour. Once built it can never disagree with itself.
 *
 * Given to you complete -- it is vocabulary, not a lesson. You will write a
 * more interesting one (ActivatedCard) in Volume One, Chapter 6.
 */
public final class Drink {

    private final String id;
    private final String displayName;
    private final int priceCents;

    public Drink(String id, String displayName, int priceCents) {
        this.id = id;
        this.displayName = displayName;
        this.priceCents = priceCents;
    }

    public String getId()          { return id; }
    public String getDisplayName() { return displayName; }
    public int getPriceCents()     { return priceCents; }

    /** Where the artwork lives on the classpath. */
    public String getImagePath() {
        return "/images/" + id + ".png";
    }

    public String getFormattedPrice() {
        return formatCents(priceCents);
    }

    /** 1250 -> "12.50". Money is ALWAYS an int of cents, never a double. */
    public static String formatCents(int cents) {
        boolean negative = cents < 0;
        int abs = Math.abs(cents);
        return (negative ? "-" : "") + (abs / 100) + "." + String.format("%02d", abs % 100);
    }

    @Override
    public String toString() {
        return displayName + " (" + getFormattedPrice() + ")";
    }
}
