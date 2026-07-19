package com.midnightbrewer.reference.error;

/**
 * The SPI transport failed: the transfer threw, returned the wrong number of
 * bytes, or the link was already closed.
 *
 * <p>Nothing above the transport layer can recover from this, because no
 * register access is possible. It means the wiring, the kernel device or the
 * Pi4J provider is broken -- not the card.
 */
public class TransportException extends NfcException {

    private static final long serialVersionUID = 1L;

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
