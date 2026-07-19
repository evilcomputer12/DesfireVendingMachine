package com.midnightbrewer.reference.pcd.crc;

import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.pcd.Rc522Driver;
import com.midnightbrewer.reference.support.SimulatedRc522;
import com.midnightbrewer.reference.support.VirtualTimebase;
import com.midnightbrewer.reference.util.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CRC_A, the checksum on the end of every ISO 14443-3 frame.
 *
 * <p>The anchor is the MIFARE HALT command. Its complete frame is documented as
 * {@code 50 00 57 CD}, so CRC_A over {@code 50 00} must be {@code 57 CD}. If
 * that holds, the preset and the polynomial are both right, and every other
 * frame this driver builds is right too.
 *
 * <p>The second thing these tests establish is that the ported
 * {@code CalulateCRC} -- which drives the RC522's coprocessor through a specific
 * sequence of register writes -- produces the same answer. That is worth
 * checking, because the sequence has an ordering requirement that is invisible
 * in the result until it is wrong: the FIFO must be flushed and {@code CRCIrq}
 * cleared before the data goes in.
 */
class CrcCalculatorTest {

    private final SoftwareCrcCalculator software = new SoftwareCrcCalculator();

    private CrcCalculator hardware() {
        SimulatedRc522 chip = new SimulatedRc522();
        Rc522Driver driver = new Rc522Driver(chip, new VirtualTimebase(), ProtocolTrace.none());
        return driver.crc();
    }

    @Test
    void haltFrameMatchesTheDocumentedVector() throws NfcException {
        byte[] crc = software.calculate(Hex.decode("50 00"));

        assertEquals("57 CD", Hex.encode(crc),
                "the documented MIFARE HALT frame is 50 00 57 CD");
    }

    @Test
    void knownVectors() throws NfcException {
        assertEquals("A0 1E", Hex.encode(software.calculate(Hex.decode("00 00"))));
        assertEquals("31 73", Hex.encode(software.calculate(Hex.decode("E0 80"))),
                "the first RATS candidate");
        assertEquals("30 3D", Hex.encode(software.calculate(Hex.decode("93 70 88 04 00 01 8D"))),
                "a cascade level 1 SELECT");
    }

    @Test
    void emptyInputYieldsThePreset() throws NfcException {
        // With no data the register still holds 0x6363, emitted low byte first.
        assertEquals("63 63", Hex.encode(software.calculate(new byte[0])));
    }

    @Test
    void hardwareCoprocessorAgreesWithSoftware() throws NfcException {
        CrcCalculator coprocessor = hardware();

        for (String vector : new String[] {
                "50 00", "00 00", "E0 80", "E0 50", "E0 20",
                "93 70 88 04 00 01 8D",
                "02 90 60 00 00 00",
                "03 90 AF 00 00 00"}) {
            byte[] data = Hex.decode(vector);
            assertArrayEquals(software.calculate(data), coprocessor.calculate(data),
                    "coprocessor disagrees on " + vector);
        }
    }

    @Test
    void hardwareCoprocessorHandlesRepeatedCallsWithoutStaleState() throws NfcException {
        CrcCalculator coprocessor = hardware();

        // A stale FIFO or an uncleared CRCIrq would show up as the second call
        // returning the first call's answer.
        byte[] first = coprocessor.calculate(Hex.decode("50 00"));
        byte[] second = coprocessor.calculate(Hex.decode("E0 80"));
        byte[] third = coprocessor.calculate(Hex.decode("50 00"));

        assertEquals("57 CD", Hex.encode(first));
        assertEquals("31 73", Hex.encode(second));
        assertArrayEquals(first, third);
    }

    @Test
    void appendToWritesTheCrcJustPastThePayload() throws NfcException {
        byte[] frame = new byte[4];
        frame[0] = 0x50;
        frame[1] = 0x00;

        software.appendTo(frame, 2);

        assertEquals("50 00 57 CD", Hex.encode(frame));
    }

    @Test
    void isValidFrameAcceptsAGoodFrameAndRejectsACorruptedOne() throws NfcException {
        byte[] good = Hex.decode("50 00 57 CD");
        assertTrue(software.isValidFrame(good, good.length));

        byte[] corrupted = Hex.decode("50 01 57 CD");
        assertFalse(software.isValidFrame(corrupted, corrupted.length));
    }

    @Test
    void isValidFrameRejectsFramesTooShortToCarryOne() throws NfcException {
        assertFalse(software.isValidFrame(Hex.decode("57 CD"), 2));
    }

    @Test
    void crcIsOrderSensitive() throws NfcException {
        assertFalse(Hex.encode(software.calculate(Hex.decode("12 34")))
                .equals(Hex.encode(software.calculate(Hex.decode("34 12")))));
    }
}
