package com.midnightbrewer.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The machine's menu.
 *
 * <p>The whole UI grid is generated from this list, so adding a drink is a
 * one-line change here — no new FXML, no new event handler. Compare that with
 * the previous version, which needed a hand-written {@code handleSelectX()}
 * method per drink; nine near-identical methods is exactly the duplication
 * that a collection is meant to remove.
 *
 * <p>Backed by a {@link LinkedHashMap} rather than a plain {@code HashMap}:
 * it gives O(1) lookup by id <em>and</em> keeps insertion order, so the grid
 * renders in the order written below instead of an arbitrary hash order.
 */
public final class DrinkCatalog {

    private static final Map<String, Drink> ITEMS = new LinkedHashMap<>();

    static {
        // Order here is the order on screen: 3 columns x 3 rows.
        add(new Drink("espresso", "Espresso", 150));
        add(new Drink("americano", "Americano", 200));
        add(new Drink("latte", "Latte", 250));
        add(new Drink("cappuccino", "Cappuccino", 300));
        add(new Drink("flatwhite", "Flat White", 300));
        add(new Drink("mocha", "Mocha", 350));
        add(new Drink("macchiato", "Macchiato", 350));
        add(new Drink("matcha", "Matcha Latte", 400));
        add(new Drink("coldbrew", "Cold Brew", 325));
    }

    private static void add(Drink drink) {
        ITEMS.put(drink.getId(), drink);
    }

    private DrinkCatalog() {
        // Static utility holder; never instantiated.
    }

    /** All drinks, in menu order. Unmodifiable — the menu is not editable at runtime. */
    public static List<Drink> all() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(ITEMS.values()));
    }

    /**
     * Look a drink up by id.
     *
     * <p>Returns an {@link Optional} rather than {@code null} so callers are
     * forced by the compiler to deal with the "no such drink" case.
     */
    public static Optional<Drink> byId(String id) {
        return Optional.ofNullable(ITEMS.get(id));
    }

    public static int size() {
        return ITEMS.size();
    }
}
