package com.midnightbrewer.reference.iso14443;

import com.midnightbrewer.reference.iso14443.block.InformationBlock;
import com.midnightbrewer.reference.iso14443.block.ProtocolBlock;
import com.midnightbrewer.reference.iso14443.block.ReceiveReadyBlock;
import com.midnightbrewer.reference.iso14443.block.SupervisoryBlock;
import com.midnightbrewer.reference.iso14443.block.UnrecognisedBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PCB classification, checked against the C's macros.
 *
 * <p>Each assertion here corresponds to one {@code PHPAL_I14443P4_SW_IS_*}
 * macro. The type hierarchy is meant to be a faithful restatement of those bit
 * tests, so this is where "faithful" gets demonstrated rather than asserted.
 */
class ProtocolBlockTest {

    @Test
    void topTwoBitsSelectTheBlockType() {
        assertInstanceOf(InformationBlock.class, ProtocolBlock.of(0x02));
        assertInstanceOf(InformationBlock.class, ProtocolBlock.of(0x03));
        assertInstanceOf(ReceiveReadyBlock.class, ProtocolBlock.of(0xA2));
        assertInstanceOf(SupervisoryBlock.class, ProtocolBlock.of(0xF2));
        assertInstanceOf(SupervisoryBlock.class, ProtocolBlock.of(0xC2));
    }

    @Test
    void anUndefinedBlockTypeIsClassifiedRatherThanRejected() {
        // 0b01xxxxxx matches none of the three defined types. The C's
        // p4_pcb_kind prints "?" for it; classification must stay total.
        ProtocolBlock block = ProtocolBlock.of(0x42);

        assertInstanceOf(UnrecognisedBlock.class, block);
        assertEquals("?", block.describe());
    }

    @Test
    void everyPossiblePcbClassifies() {
        for (int pcb = 0; pcb <= 0xFF; pcb++) {
            assertTrue(ProtocolBlock.of(pcb) != null, "PCB " + pcb);
        }
    }

    @Test
    void chainingBitDistinguishesChainedIBlocks() {
        assertFalse(((InformationBlock) ProtocolBlock.of(0x02)).isChaining());
        assertTrue(((InformationBlock) ProtocolBlock.of(0x12)).isChaining());
        assertTrue(((InformationBlock) ProtocolBlock.of(0x13)).isChaining());
    }

    @Test
    void withChainingSetsAndClearsWithoutTouchingTheBlockNumber() {
        InformationBlock plain = InformationBlock.of(0x03);

        InformationBlock chained = plain.withChaining(true);
        assertEquals((byte) 0x13, chained.pcb());
        assertEquals(1, chained.blockNumber());

        assertEquals((byte) 0x03, chained.withChaining(false).pcb());
    }

    @Test
    void withChainingReturnsANewBlockLeavingTheOriginalAlone() {
        InformationBlock original = InformationBlock.of(0x02);
        original.withChaining(true);

        assertEquals((byte) 0x02, original.pcb(), "blocks are immutable");
    }

    @Test
    void informationBlockFactoryRejectsOtherBlockTypes() {
        assertThrows(IllegalArgumentException.class, () -> InformationBlock.of(0xA2));
    }

    @Test
    void receiveReadyBlockDistinguishesAckFromNak() {
        // IS_ACK is (pcb & 0x10) == 0.
        ReceiveReadyBlock ack = (ReceiveReadyBlock) ProtocolBlock.of(0xA2);
        ReceiveReadyBlock nak = (ReceiveReadyBlock) ProtocolBlock.of(0xB2);

        assertTrue(ack.isAcknowledgement());
        assertFalse(ack.isNegativeAcknowledgement());
        assertEquals("R-ACK", ack.describe());

        assertTrue(nak.isNegativeAcknowledgement());
        assertEquals("R-NAK", nak.describe());
    }

    @Test
    void supervisoryBlockDistinguishesWtxFromDeselect() {
        // IS_WTX is (pcb & 0x30) == 0x30; IS_DESELECT is (pcb & 0x30) == 0x00.
        SupervisoryBlock wtx = (SupervisoryBlock) ProtocolBlock.of(0xF2);
        SupervisoryBlock deselect = (SupervisoryBlock) ProtocolBlock.of(0xC2);

        assertTrue(wtx.isWaitingTimeExtension());
        assertFalse(wtx.isDeselect());
        assertEquals("S-WTX", wtx.describe());

        assertTrue(deselect.isDeselect());
        assertFalse(deselect.isWaitingTimeExtension());
        assertEquals("S-DESEL", deselect.describe());
    }

    @Test
    void supervisoryFactoriesProduceTheBytesTheCSends() {
        // S_BLOCK | RFU | WTX = 0xC0 | 0x02 | 0x30
        assertEquals((byte) 0xF2, SupervisoryBlock.waitingTimeExtension().pcb());
        // S_BLOCK | RFU | DESELECT = 0xC0 | 0x02 | 0x00
        assertEquals((byte) 0xC2, SupervisoryBlock.deselect().pcb());
    }

    @Test
    void cidBitIsRecognisedOnAnyBlockType() {
        assertTrue(ProtocolBlock.of(0x0A).hasCid());
        assertFalse(ProtocolBlock.of(0x02).hasCid());
    }
}
