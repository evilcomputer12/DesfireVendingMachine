package com.midnightbrewer.card;

/**
 * The CMAC on a response did not verify.
 *
 * <p>Maps to {@code DF_ERR_CMAC}. Treat this as hostile until proven
 * otherwise: after a successful authentication, every response is MACed with
 * the session key over {@code [status, cmdCounter, TI, data]}. A mismatch
 * means either the command counter desynchronised, or something on the RF
 * link tampered with the bytes.
 *
 * <p>The only safe response is to abandon the session entirely and
 * re-authenticate. Do not retry the command on the existing session.
 */
public class CmacMismatchException extends CardException {

    private static final long serialVersionUID = 1L;

    public CmacMismatchException(String message) {
        super(message);
    }

    @Override
    public String getUserMessage() {
        return "Secure channel error. Transaction cancelled.";
    }
}
