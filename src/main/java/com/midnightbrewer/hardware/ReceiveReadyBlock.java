package com.midnightbrewer.hardware;

public class ReceiveReadyBlock extends ProtocolBlock {
    public ReceiveReadyBlock(int pcb) {
        super(pcb);   // hand the PCB up to the base to store
    }

    @Override
    public String describe() {
        return "R-block (acknowledgement) for block number " + (pcb() & 0x01);
    }
}