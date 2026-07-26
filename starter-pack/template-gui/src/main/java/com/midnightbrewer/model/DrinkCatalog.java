package com.midnightbrewer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The menu. Edit freely -- the grid sizes itself to whatever is here. */
public final class DrinkCatalog {

    private static final List<Drink> DRINKS = new ArrayList<>();

    static {
        DRINKS.add(new Drink("espresso",   "Espresso",   150));
        DRINKS.add(new Drink("americano",  "Americano",  200));
        DRINKS.add(new Drink("latte",      "Latte",      250));
        DRINKS.add(new Drink("cappuccino", "Cappuccino", 300));
        DRINKS.add(new Drink("flatwhite",  "Flat White", 300));
        DRINKS.add(new Drink("mocha",      "Mocha",      350));
    }

    private DrinkCatalog() { }

    /** Unmodifiable: callers may read the menu, never rewrite it. */
    public static List<Drink> all() {
        return Collections.unmodifiableList(DRINKS);
    }

    public static int size() {
        return DRINKS.size();
    }
}
