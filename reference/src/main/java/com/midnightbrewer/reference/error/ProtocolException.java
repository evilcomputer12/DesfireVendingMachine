package com.midnightbrewer.reference.error;

/**
 * An activated card broke the ISO 14443-4 block protocol.
 *
 * <p>Examples: an I-block arriving where an R(ACK) was required to continue a
 * chain, a PCB whose top two bits match none of the three block types, or a
 * card that asked for more waiting-time extensions than the driver is willing
 * to grant.
 *
 * <p>This is the failure that matters most during bring-up, because it means
 * the RF layer works and the disagreement is about protocol state. The message
 * therefore carries the offending PCB whenever one is available.
 */
public class ProtocolException extends CardException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
