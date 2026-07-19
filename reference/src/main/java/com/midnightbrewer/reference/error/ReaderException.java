package com.midnightbrewer.reference.error;

/**
 * The RC522 itself misbehaved: it answered on SPI, but not in a way the
 * datasheet allows.
 *
 * <p>The typical cases are the CRC coprocessor never raising {@code CRCIrq},
 * or {@code VersionReg} reading back something that is not a known silicon
 * revision. Both point at the module or its 3.3 V rail rather than at any card.
 */
public class ReaderException extends NfcException {

    private static final long serialVersionUID = 1L;

    public ReaderException(String message) {
        super(message);
    }

    public ReaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
