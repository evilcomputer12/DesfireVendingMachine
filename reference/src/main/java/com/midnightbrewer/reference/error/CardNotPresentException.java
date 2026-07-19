package com.midnightbrewer.reference.error;

/**
 * No card answered inside the RC522's hardware timeout.
 *
 * <p>Corresponds to {@code MI_NOTAGERR} in the C driver, and to an expired
 * {@code TimerIRq} with no reply. In a vending machine this is the normal
 * state of the world, so it is worth having its own type: callers poll on it
 * rather than treating it as a fault.
 */
public class CardNotPresentException extends CardException {

    private static final long serialVersionUID = 1L;

    public CardNotPresentException(String message) {
        super(message);
    }
}
