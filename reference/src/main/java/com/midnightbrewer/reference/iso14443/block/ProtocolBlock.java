package com.midnightbrewer.reference.iso14443.block;

import com.midnightbrewer.reference.util.Hex;

/**
 * One ISO 14443-4 block, identified by its Protocol Control Byte.
 *
 * <p>Frame layout, from the comment block in {@code RC522.c}:
 *
 * <pre>
 *  |-----|-----|-----|----------------|-----|
 *  | PCB | CID | NAD |      INF       | EDC |
 *  |-----|-----|-----|----------------|-----|
 * </pre>
 *
 * <p>The C classifies a PCB with six macros that expand to bit tests --
 * {@code PHPAL_I14443P4_SW_IS_I_BLOCK}, {@code ..._IS_ACK},
 * {@code ..._IS_WTX} and friends -- and every use site is an {@code if}
 * chain over them. It works, but the type of a block is never represented, so
 * nothing stops code from asking whether an I-block is an ACK, and the compiler
 * cannot tell you that a branch is missing.
 *
 * <p>Here the top two bits of the PCB choose a subclass once, at the boundary,
 * and each subclass exposes only the questions that make sense for it. The
 * hierarchy is sealed and total: {@link #of(int)} always returns something, and
 * a {@code switch} over the four permitted types is exhaustive. The bit tests
 * themselves are unchanged -- same masks, same comparisons -- so the wire
 * behaviour is identical.
 */
public abstract sealed class ProtocolBlock
        permits InformationBlock, ReceiveReadyBlock, SupervisoryBlock, UnrecognisedBlock {

    /** Offset of the PCB in a frame. {@code PHPAL_I14443P4_SW_PCB_POS}. */
    public static final int PCB_POSITION = 0;

    /** Bits 7..6 select the block type. {@code PHPAL_I14443P4_SW_BLOCK_MASK}. */
    static final int BLOCK_TYPE_MASK = 0xC0;

    /** Block type I: an information block. {@code PHPAL_I14443P4_SW_I_BLOCK}. */
    static final int TYPE_INFORMATION = 0x00;

    /** Block type R: receive-ready, an ACK or NAK. {@code PHPAL_I14443P4_SW_R_BLOCK}. */
    static final int TYPE_RECEIVE_READY = 0x80;

    /** Block type S: supervisory, a WTX or DESELECT. {@code PHPAL_I14443P4_SW_S_BLOCK}. */
    static final int TYPE_SUPERVISORY = 0xC0;

    /** Bit 0 carries the block number. {@code PHPAL_I14443P4_SW_PCB_BLOCKNR}. */
    static final int BLOCK_NUMBER_BIT = 0x01;

    /**
     * Bit 3: a CID byte follows the PCB. This driver never sends one, but it
     * must still be honoured when parsing, because it shifts where INF starts.
     */
    static final int CID_BIT = 0x08;

    /**
     * Bit 4. In an I-block it means "chaining"; in an R-block it distinguishes
     * NAK from ACK. Same bit, two meanings, which is exactly the kind of overlap
     * a type hierarchy exists to keep straight.
     * {@code PHPAL_I14443P4_SW_PCB_CHAINING} / {@code ..._PCB_NAK}.
     */
    static final int CHAINING_OR_NAK_BIT = 0x10;

    private final byte pcb;

    ProtocolBlock(int pcb) {
        this.pcb = (byte) pcb;
    }

    /**
     * Classifies a PCB into exactly one block type.
     *
     * <p>Never returns null and never throws: a PCB of {@code 0x40} matches
     * none of the three defined types and becomes an
     * {@link UnrecognisedBlock}, which is what the C's {@code p4_pcb_kind}
     * reports as {@code "?"}.
     */
    public static ProtocolBlock of(int pcb) {
        switch (pcb & BLOCK_TYPE_MASK) {
            case TYPE_INFORMATION:
                return new InformationBlock(pcb);
            case TYPE_RECEIVE_READY:
                return new ReceiveReadyBlock(pcb);
            case TYPE_SUPERVISORY:
                return new SupervisoryBlock(pcb);
            default:
                return new UnrecognisedBlock(pcb);
        }
    }

    /** The raw protocol control byte. */
    public final byte pcb() {
        return pcb;
    }

    /** The PCB as an unsigned value. */
    public final int pcbValue() {
        return pcb & 0xFF;
    }

    /**
     * True if a CID byte follows the PCB, moving the start of INF from offset 1
     * to offset 2. Ported from the {@code (frame[0] & 0x08)} test in
     * {@code p4_inf_field}.
     */
    public final boolean hasCid() {
        return (pcb & CID_BIT) != 0;
    }

    /** A short label for logs, matching the C's {@code p4_pcb_kind}. */
    public abstract String describe();

    @Override
    public final String toString() {
        return describe() + "[PCB=" + Hex.byteToString(pcb) + "]";
    }

    @Override
    public final boolean equals(Object other) {
        return other != null && other.getClass() == getClass()
                && ((ProtocolBlock) other).pcb == pcb;
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode() * 31 + pcb;
    }
}
