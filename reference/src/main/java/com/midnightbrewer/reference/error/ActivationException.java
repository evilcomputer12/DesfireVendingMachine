package com.midnightbrewer.reference.error;

/**
 * A card was in the field but could not be driven from IDLE to ISO 14443-4.
 *
 * <p>Raised by anticollision (bad BCC), SELECT (SAK of zero) and RATS (no ATS
 * from any of the three FSDI candidates). Distinct from
 * {@link CardNotPresentException} because the card <em>did</em> answer -- so
 * the field and antenna are fine, and the fault is in the activation sequence,
 * the card type, or RF quality at the edge of range.
 */
public class ActivationException extends CardException {

    private static final long serialVersionUID = 1L;

    public ActivationException(String message) {
        super(message);
    }

    public ActivationException(String message, Throwable cause) {
        super(message, cause);
    }
}
