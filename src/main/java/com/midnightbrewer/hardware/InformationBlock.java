package com.midnightbrewer.hardware;

public class InformationBlock extends ProtocolBlock {
    public InformationBlock(int pcb){
        super(pcb);
    }
    @Override
    public String describe(){
        return "I-Block (data), block number " + (pcb() & 0x01);
    }

    /**
     * M6: encode this I-block into a frame -- its PCB, then the data it carries.
     *
     * This is the block gaining BEHAVIOUR. In M4 it could only describe()
     * itself; now it does real work: turn "an I-block carrying these bytes"
     * into the actual bytes on the wire, [pcb, ...inf...].
     */
    public byte[] encode(byte[] inf) {
        // TODO: make a byte[inf.length + 1].
        //       Put the PCB in slot 0:   frame[0] = (byte) pcb();
        //       Copy inf in after it:    System.arraycopy(inf, 0, frame, 1, inf.length);
        //       return frame;
        return new byte[0]; // placeholder
    }
}
