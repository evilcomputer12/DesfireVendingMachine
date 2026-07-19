package com.midnightbrewer.reference.iso14443.block;

/**
 * An I-block: the one that actually carries an APDU.
 *
 * <p>Everything else in the protocol exists to move I-blocks around. The two
 * bits that matter are the block number in bit 0, which the reader and card
 * toggle in step so that a retransmission can be told from a new block, and the
 * chaining flag in bit 4, which says "there is more of this APDU coming".
 *
 * <p>Immutable. {@link #withChaining} returns a new block rather than editing
 * this one, so a PCB cannot be modified after it has been used to build a
 * frame.
 */
public final class InformationBlock extends ProtocolBlock {

    InformationBlock(int pcb) {
        super(pcb);
    }

    /**
     * Wraps a PCB known to be an I-block.
     *
     * @throws IllegalArgumentException if the top two bits say otherwise
     */
    public static InformationBlock of(int pcb) {
        if ((pcb & BLOCK_TYPE_MASK) != TYPE_INFORMATION) {
            throw new IllegalArgumentException(
                    String.format("PCB 0x%02X is not an I-block", pcb & 0xFF));
        }
        return new InformationBlock(pcb);
    }

    /**
     * True if this block is one of several carrying a single APDU, and the card
     * should answer with an R(ACK) rather than a response.
     * {@code PHPAL_I14443P4_SW_PCB_CHAINING}.
     */
    public boolean isChaining() {
        return (pcbValue() & CHAINING_OR_NAK_BIT) != 0;
    }

    /** The block number, 0 or 1. {@code PHPAL_I14443P4_SW_PCB_BLOCKNR}. */
    public int blockNumber() {
        return pcbValue() & BLOCK_NUMBER_BIT;
    }

    /**
     * This block with the chaining flag set or cleared.
     *
     * <p>{@code MFRC522_14443P4_Transceive} builds each outgoing PCB exactly
     * this way: take the next block number from the toggle, then OR in chaining
     * if more of the APDU remains.
     */
    public InformationBlock withChaining(boolean chaining) {
        int updated = chaining
                ? pcbValue() | CHAINING_OR_NAK_BIT
                : pcbValue() & ~CHAINING_OR_NAK_BIT;
        return new InformationBlock(updated);
    }

    @Override
    public String describe() {
        return "I";
    }
}
