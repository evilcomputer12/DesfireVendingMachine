package com.midnightbrewer.hardware;

/**
 * M4 — one ISO-14443-4 (T=CL) block.
 *
 * Every frame you exchange with an activated card is one of three block types.
 * They all have a PCB (the first byte), so that shared state lives here in the
 * base -- which is why this is an abstract CLASS, not an interface (an
 * interface can't hold the pcb field).
 *
 * What DIFFERS per type is behaviour: an I-block carries data, an R-block is an
 * acknowledgement, an S-block is control. So {@link #describe()} is left
 * 'abstract' -- each subclass answers it its own way. That is polymorphism:
 * one method name, three behaviours, chosen by the real object's type.
 */
public abstract class ProtocolBlock {

    // Shared state: the Protocol Control Byte. 'protected' constructor because
    // only subclasses ever build a block (via the factory below).
    private final int pcb;

    protected ProtocolBlock(int pcb) {
        this.pcb = pcb & 0xFF;
    }

    /** The raw PCB byte (0..255). */
    public int pcb() {
        return pcb;
    }

    /**
     * A human-readable description. Declared here, no body -- each subclass
     * MUST provide one. THIS is the method that behaves differently per type.
     */
    public abstract String describe();

    // ═════════════════════════════════════════════════════════════════
    // THE ONE PLACE the type is decided.
    //
    // The whole point of M4: this switch on the PCB bits exists ONCE, here.
    // Everywhere else in the code you just hold a ProtocolBlock and call
    // describe() (or any future method) -- no type-checking, no switch.
    //
    // PCB top two bits tell the type (ISO 14443-4):
    //     b8 = 0        -> I-block
    //     b8 = 1, b7 = 0 -> R-block
    //     b8 = 1, b7 = 1 -> S-block
    //
    // This factory is a stub for now -- you'll fill it once the three
    // subclasses exist.
    // ═════════════════════════════════════════════════════════════════
    public static ProtocolBlock from(int pcb) {
        if ((pcb & 0x80) == 0) return new InformationBlock(pcb);   // b8 = 0  -> I
        if ((pcb & 0x40) == 0) return new ReceiveReadyBlock(pcb);  // b8=1,b7=0 -> R
            return new SupervisoryBlock(pcb);      
    }                    // b8=1,b7=1 -> S
}
