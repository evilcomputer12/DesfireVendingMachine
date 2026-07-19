package com.midnightbrewer.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The one piece of {@link WalletDemo} that has no hardware in it: money formatting. */
class WalletDemoTest {

    @Test
    void formatsCentsAsEuroStrings() {
        assertEquals("25.00", WalletDemo.formatCents(2500));
        assertEquals("21.50", WalletDemo.formatCents(2150));
        assertEquals("0.05", WalletDemo.formatCents(5));
        assertEquals("0.00", WalletDemo.formatCents(0));
        assertEquals("-1.25", WalletDemo.formatCents(-125));
    }
}
