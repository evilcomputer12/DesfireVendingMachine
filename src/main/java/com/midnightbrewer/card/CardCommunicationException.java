package com.midnightbrewer.card;

/**
 * The RF link failed: timeout, framing error, CRC error, or the card was
 * pulled out of the field mid-exchange.
 *
 * <p>Maps to {@code DF_ERR_TRANSCEIVE} / {@code DF_ERR_NO_CARD} in the C code.
 * This one is usually <em>recoverable</em> — the right response is "hold your
 * card still and tap again", not "your card is broken".
 */
public class CardCommunicationException extends CardException {

    private static final long serialVersionUID = 1L;

    public CardCommunicationException(String message) {
        super(message);
    }

    public CardCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getUserMessage() {
        return "Card lost. Hold it flat on the reader and try again.";
    }
}
