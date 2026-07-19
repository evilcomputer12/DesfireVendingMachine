package com.midnightbrewer.reference.error;

/**
 * Something went wrong on the card side of the link, with a healthy reader.
 *
 * <p>Abstract because "a card problem" is never actionable on its own: the
 * useful distinction is between a card that is not there
 * ({@link CardNotPresentException}), one that could not be brought up
 * ({@link ActivationException}), and one that broke the block protocol once it
 * was up ({@link ProtocolException}).
 */
public abstract class CardException extends NfcException {

    private static final long serialVersionUID = 1L;

    protected CardException(String message) {
        super(message);
    }

    protected CardException(String message, Throwable cause) {
        super(message, cause);
    }
}
