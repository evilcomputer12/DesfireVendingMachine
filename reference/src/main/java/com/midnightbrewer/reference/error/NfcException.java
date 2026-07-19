package com.midnightbrewer.reference.error;

/**
 * Root of the checked exception hierarchy for everything that can go wrong
 * between this process and a contactless card.
 *
 * <p>The C driver signals failure with three {@code uchar} codes -- {@code MI_OK},
 * {@code MI_NOTAGERR}, {@code MI_ERR} -- and every caller has to remember which
 * of the three a given function can return, and what "error" meant in that
 * context. Two thirds of a real diagnosis is lost at the return statement.
 *
 * <p>This hierarchy keeps that information. Each subclass answers a different
 * question, and the answers lead to different fixes:
 *
 * <ul>
 *   <li>{@link TransportException} -- the SPI bus itself failed. Suspect wiring,
 *       permissions on {@code /dev/spidev0.0}, or the Pi4J providers.</li>
 *   <li>{@link ReaderException} -- the RC522 answered but did something
 *       impossible. Suspect the chip, its power rail, or a bad register write.</li>
 *   <li>{@link CardException} -- the reader is healthy and the card is at
 *       fault, missing, or misbehaving.</li>
 * </ul>
 *
 * <p>These are checked exceptions deliberately. Radio links fail routinely --
 * a card leaves the field mid-transaction and that is normal operation, not a
 * bug -- so callers should be forced to decide what happens next.
 */
public abstract class NfcException extends Exception {

    private static final long serialVersionUID = 1L;

    protected NfcException(String message) {
        super(message);
    }

    protected NfcException(String message, Throwable cause) {
        super(message, cause);
    }
}
