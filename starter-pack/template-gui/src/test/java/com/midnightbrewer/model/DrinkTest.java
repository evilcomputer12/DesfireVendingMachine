package com.midnightbrewer.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the toolchain and pins one real rule: money is an int of cents.
 *
 * Never a double. 0.1 + 0.2 is not 0.3 in binary floating point, and a vending
 * machine that is a hundredth of a cent out per transaction is a vending
 * machine that does not balance at the end of the week.
 */
class DrinkTest {

    @Test
    void formatsCentsAsEuros() {
        assertEquals("1.50", Drink.formatCents(150));
        assertEquals("0.05", Drink.formatCents(5));
        assertEquals("12.50", Drink.formatCents(1250));
        assertEquals("0.00", Drink.formatCents(0));
        assertEquals("-1.50", Drink.formatCents(-150));
    }

    @Test
    void drinkKnowsItsArtwork() {
        Drink d = new Drink("espresso", "Espresso", 150);
        assertEquals("/images/espresso.png", d.getImagePath());
        assertEquals("1.50", d.getFormattedPrice());
    }

    @Test
    void catalogIsNotEmptyAndIsUnmodifiable() {
        assertTrue(DrinkCatalog.size() > 0);
        try {
            DrinkCatalog.all().add(new Drink("x", "X", 1));
            throw new AssertionError("the catalog should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // correct: callers may read the menu, never rewrite it
        }
    }
}
