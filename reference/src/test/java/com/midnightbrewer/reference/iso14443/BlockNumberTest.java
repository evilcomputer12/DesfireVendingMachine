package com.midnightbrewer.reference.iso14443;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The block number toggle, ported from the C's {@code get_pcb()}.
 *
 * <p>Small enough to look obviously correct and important enough to test
 * anyway: a toggle that starts at the wrong value, or returns the new value
 * instead of the old one, produces a card that answers every second command and
 * ignores the rest. That symptom is indistinguishable from a marginal antenna
 * until someone reads the PCBs off a logic analyser.
 */
class BlockNumberTest {

    @Test
    void startsAtTheValueBothSidesAssumeAfterRats() {
        assertEquals(0x02, new BlockNumber().peek());
    }

    @Test
    void alternatesBetweenTwoAndThree() {
        BlockNumber blockNumber = new BlockNumber();

        assertEquals((byte) 0x02, blockNumber.nextInformationPcb());
        assertEquals((byte) 0x03, blockNumber.nextInformationPcb());
        assertEquals((byte) 0x02, blockNumber.nextInformationPcb());
        assertEquals((byte) 0x03, blockNumber.nextInformationPcb());
    }

    @Test
    void returnsTheCurrentValueAndAdvancesAfterwards() {
        BlockNumber blockNumber = new BlockNumber();

        int before = blockNumber.peek();
        byte issued = blockNumber.nextInformationPcb();

        assertEquals(before, issued & 0xFF, "the value issued is the one before the toggle");
        assertNotEquals(before, blockNumber.peek(), "and the toggle has moved on");
    }

    @Test
    void reservedBitOneIsSetInEveryIssuedPcb() {
        BlockNumber blockNumber = new BlockNumber();

        for (int i = 0; i < 10; i++) {
            int pcb = blockNumber.nextInformationPcb() & 0xFF;
            assertEquals(0x02, pcb & 0x02,
                    "ISO 14443-4 requires bit 1 set in every I-block PCB");
        }
    }

    @Test
    void issuedPcbsAreAlwaysIBlocks() {
        BlockNumber blockNumber = new BlockNumber();

        for (int i = 0; i < 10; i++) {
            int pcb = blockNumber.nextInformationPcb() & 0xFF;
            assertEquals(0x00, pcb & 0xC0, "top two bits must say I-block");
        }
    }

    @Test
    void resetReturnsToTheStartOfTheSequence() {
        BlockNumber blockNumber = new BlockNumber();
        blockNumber.nextInformationPcb();
        blockNumber.nextInformationPcb();
        blockNumber.nextInformationPcb();

        blockNumber.reset();

        assertEquals((byte) 0x02, blockNumber.nextInformationPcb(),
                "after RATS or a field reset the card's counter is back at zero too");
    }

    @Test
    void twoTogglesAreIndependent() {
        // The C keeps this in a file-scope static, so two readers in one process
        // would share it. These must not.
        BlockNumber first = new BlockNumber();
        BlockNumber second = new BlockNumber();

        first.nextInformationPcb();

        assertEquals(0x03, first.peek());
        assertEquals(0x02, second.peek());
    }
}
