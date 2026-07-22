package com.midnightbrewer.hardware;

public class InformationBlock extends ProtocolBlock {
    public InformationBlock(int pcb){
        super(pcb);
    }
    @Override
    public String describe(){
        return "I-Block (data), block number " + (pcb() & 0x01);
    }
}
