package com.midnightbrewer.reference.iso14443.block;

/**
 * An R-block: an acknowledgement, carrying no data.
 *
 * <p>The driver sees exactly one kind in normal operation -- the R(ACK) a card
 * sends after each non-final block of a chained APDU. Getting anything else
 * there means the card has lost the thread, and
 * {@code MFRC522_14443P4_Transceive} abandons the exchange rather than trying to
 * recover.
 *
 * <p>ACK and NAK share bit 4 with the I-block chaining flag, which is why the
 * C's {@code PHPAL_I14443P4_SW_IS_ACK} macro is meaningless unless you have
 * already established the block is an R-block. Here that precondition is the
 * type.
 */
public final class ReceiveReadyBlock extends ProtocolBlock {

    ReceiveReadyBlock(int pcb) {
        super(pcb);
    }

    /**
     * True if this is a positive acknowledgement -- bit 4 clear.
     * {@code PHPAL_I14443P4_SW_IS_ACK}.
     */
    public boolean isAcknowledgement() {
        return (pcbValue() & CHAINING_OR_NAK_BIT) == 0;
    }

    /** True if this is a negative acknowledgement: bit 4 set. */
    public boolean isNegativeAcknowledgement() {
        return !isAcknowledgement();
    }

    /** The block number this R-block refers to, 0 or 1. */
    public int blockNumber() {
        return pcbValue() & BLOCK_NUMBER_BIT;
    }

    @Override
    public String describe() {
        return isAcknowledgement() ? "R-ACK" : "R-NAK";
    }
}
