package com.midnightbrewer.card;

/**
 * AuthenticateEV2First did not complete: wrong key, wrong key number, or the
 * card's {@code RndA'} did not match what we expected.
 *
 * <p>Maps to {@code DF_ERR_AUTH}. Note the security property this protects:
 * authentication is <em>mutual</em>. A failure here can equally mean "this
 * card is not ours" or "this reader is not the card's", and a cloned or
 * spoofed card fails at exactly this step. Never fall back to trusting the
 * UID when this throws — a UID is public and trivially forged.
 */
public class AuthenticationFailedException extends CardException {

    private static final long serialVersionUID = 1L;

    private final int keyNumber;

    public AuthenticationFailedException(String message, int keyNumber) {
        super(message);
        this.keyNumber = keyNumber;
    }

    public int getKeyNumber() {
        return keyNumber;
    }

    @Override
    public String getUserMessage() {
        return "This card is not accepted here.";
    }
}
