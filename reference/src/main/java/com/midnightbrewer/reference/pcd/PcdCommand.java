package com.midnightbrewer.reference.pcd;

/**
 * Commands written to {@link Register#COMMAND}, from chapter 10 of the datasheet.
 *
 * <p>Each constant carries the two interrupt masks {@code MFRC522_ToCard} picks
 * for it. In the C those masks live in a {@code switch} inside the transceive
 * routine, far from the command values themselves; keeping them on the command
 * means a new command cannot be added without deciding which interrupts it
 * waits on.
 *
 * <ul>
 *   <li>{@code interruptEnableMask} goes to {@code CommIEnReg} (with bit 7 set,
 *       which makes the write mean "enable these").</li>
 *   <li>{@code waitInterruptMask} is the set of {@code CommIrqReg} bits whose
 *       arrival ends the wait loop.</li>
 * </ul>
 */
public enum PcdCommand {

    /** No action; cancels the command in progress. */
    IDLE(0x00, 0x00, 0x00),

    /** MIFARE Classic authentication. Waits on IdleIRq only. */
    AUTHENTICATE(0x0E, 0x12, 0x10),

    /** Activates the receiver circuits. */
    RECEIVE(0x08, 0x00, 0x00),

    /** Transmits the FIFO contents. */
    TRANSMIT(0x04, 0x00, 0x00),

    /**
     * Transmits the FIFO and switches to receive automatically.
     *
     * <p>The wait mask is {@code 0x30} -- RxIRq or IdleIRq. The C comments this
     * as "see post-wait idle drain below": waking on IdleIRq means the command
     * ended before the frame was fully in the FIFO, which is exactly the case
     * the ISO 14443-4 layer's FIFO settle loop exists to clean up after.
     */
    TRANSCEIVE(0x0C, 0x77, 0x30),

    /** Soft reset. */
    SOFT_RESET(0x0F, 0x00, 0x00),

    /** Runs the CRC coprocessor over the FIFO contents. */
    CALCULATE_CRC(0x03, 0x00, 0x00);

    private final int code;
    private final int interruptEnableMask;
    private final int waitInterruptMask;

    PcdCommand(int code, int interruptEnableMask, int waitInterruptMask) {
        this.code = code;
        this.interruptEnableMask = interruptEnableMask;
        this.waitInterruptMask = waitInterruptMask;
    }

    /** The value written to {@link Register#COMMAND}. */
    public int code() {
        return code;
    }

    /** Bits for {@code CommIEnReg}, before bit 7 is added. */
    public int interruptEnableMask() {
        return interruptEnableMask;
    }

    /** {@code CommIrqReg} bits that end the wait loop for this command. */
    public int waitInterruptMask() {
        return waitInterruptMask;
    }
}
