package com.midnightbrewer.hardware;

public class SupervisoryBlock extends ProtocolBlock {
    public SupervisoryBlock(int pcb) {
        super(pcb);   // hand the PCB up to the base to store
    }

    @Override
    public String describe() {
        // No block number here on purpose: S-blocks are control frames (WTX,
        // DESELECT) and, unlike I- and R-blocks, carry no block number.
        return "S-block (supervisory)";
    }
}