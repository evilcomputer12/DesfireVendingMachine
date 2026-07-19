package com.midnightbrewer.reference.iso14443.block;

/**
 * An S-block: protocol control rather than data.
 *
 * <p>Two matter here. S(WTX) is a card saying "I am still working, give me
 * longer" -- a DESFire committing a key change or writing to EEPROM sends
 * these, and a reader that does not answer them will time out on a card that
 * was about to succeed. S(DESELECT) ends the session.
 *
 * <p>Both are distinguished by bits 5..4, and the C tests them with the same
 * {@code PHPAL_I14443P4_SW_PCB_WTX} mask ({@code 0x30}) in both directions:
 * equal to {@code 0x30} means WTX, equal to {@code 0x00} means DESELECT. That
 * asymmetric-looking pair of tests is reproduced exactly.
 *
 * <p>Bit 1 is set in every S-block this driver sends
 * ({@code PHPAL_I14443P4_SW_S_BLOCK_RFU_BITS = 0x02}). It is reserved, and the
 * C sets it; so does this.
 */
public final class SupervisoryBlock extends ProtocolBlock {

    /** Bits 5..4 select the S-block function. {@code PHPAL_I14443P4_SW_PCB_WTX}. */
    private static final int FUNCTION_MASK = 0x30;

    /** Bits 5..4 == 0b11: waiting time extension. */
    private static final int FUNCTION_WTX = 0x30;

    /** Bits 5..4 == 0b00: deselect. {@code PHPAL_I14443P4_SW_PCB_DESELECT}. */
    private static final int FUNCTION_DESELECT = 0x00;

    /** Bit 1, reserved but always set by this driver. */
    private static final int RFU_BITS = 0x02;

    SupervisoryBlock(int pcb) {
        super(pcb);
    }

    /** The S(WTX) reply the driver sends: {@code 0xF2}. */
    public static SupervisoryBlock waitingTimeExtension() {
        return new SupervisoryBlock(TYPE_SUPERVISORY | RFU_BITS | FUNCTION_WTX);
    }

    /** The S(DESELECT) request the driver sends: {@code 0xC2}. */
    public static SupervisoryBlock deselect() {
        return new SupervisoryBlock(TYPE_SUPERVISORY | RFU_BITS | FUNCTION_DESELECT);
    }

    /** {@code PHPAL_I14443P4_SW_IS_WTX}: bits 5..4 both set. */
    public boolean isWaitingTimeExtension() {
        return (pcbValue() & FUNCTION_MASK) == FUNCTION_WTX;
    }

    /** {@code PHPAL_I14443P4_SW_IS_DESELECT}: bits 5..4 both clear. */
    public boolean isDeselect() {
        return (pcbValue() & FUNCTION_MASK) == FUNCTION_DESELECT;
    }

    @Override
    public String describe() {
        if (isWaitingTimeExtension()) {
            return "S-WTX";
        }
        if (isDeselect()) {
            return "S-DESEL";
        }
        return "S";
    }
}
