package com.midnightbrewer.hardware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Proves the M4 polymorphism -- no hardware, pure logic.
 */
class ProtocolBlockTest {

    /** The factory reads the PCB bits and builds the right subclass. */
    @Test
    void factoryPicksTheRightTypeFromThePcb() {
        assertInstanceOf(InformationBlock.class, ProtocolBlock.from(0x02));  // b8=0
        assertInstanceOf(ReceiveReadyBlock.class, ProtocolBlock.from(0xA2)); // b8=1,b7=0
        assertInstanceOf(SupervisoryBlock.class, ProtocolBlock.from(0xC2));  // b8=1,b7=1
    }

    /**
     * THE polymorphism demo. from() hands back a ProtocolBlock. The loop never
     * checks which kind it is -- yet describe() does the right thing for each,
     * because the REAL object's type decides. One call site, three behaviours.
     */
    @Test
    void oneCallThreeBehaviours() {
        int[] pcbs = {0x02, 0xA2, 0xC2};
        for (int pcb : pcbs) {
            ProtocolBlock block = ProtocolBlock.from(pcb); // declared type: ProtocolBlock
            System.out.printf("PCB 0x%02X -> %s%n", pcb, block.describe());
        }
        // different real types really do behave differently:
        assertNotEquals(
                ProtocolBlock.from(0x02).describe(),
                ProtocolBlock.from(0xA2).describe());
    }
}
