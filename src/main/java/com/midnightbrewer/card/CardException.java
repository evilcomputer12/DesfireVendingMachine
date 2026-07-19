package com.midnightbrewer.card;

/**
 * Base type for everything that can go wrong talking to a card.
 *
 * <p>This hierarchy is the Java replacement for the {@code DFStatus} integer
 * codes in your C library ({@code DF_ERR_AUTH}, {@code DF_ERR_CMAC}, ...).
 * The difference matters: a C caller can ignore a returned {@code -3} and
 * carry on with garbage, whereas a checked exception cannot be ignored — the
 * compiler forces every caller to either handle it or declare it.
 *
 * <p>Catch the subclasses when you want to react differently (retry vs.
 * refuse), catch {@code CardException} when you just need to abort cleanly.
 */
public abstract class CardException extends Exception {

    private static final long serialVersionUID = 1L;

    protected CardException(String message) {
        super(message);
    }

    protected CardException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Short, non-technical text safe to show on the kiosk screen. */
    public abstract String getUserMessage();
}
