package com.midnightbrewer.reference.pcd;

import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.spi.SpiLink;
import com.midnightbrewer.reference.support.SimulatedRc522;
import com.midnightbrewer.reference.support.VirtualTimebase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SPI address encoding: {@code ((reg << 1) & 0x7E) | 0x80} for a read, the
 * same shift without bit 7 for a write.
 *
 * <p>Worth testing on its own because it is the single point where a mistake
 * makes every register access wrong at once, and because the symptom -- a
 * version register that reads as some other register's value -- looks like
 * broken hardware rather than a shift.
 */
class RegisterEncodingTest {

    /** Records the exact bytes clocked out, so the encoding can be inspected. */
    private static final class RecordingLink implements SpiLink {
        final List<byte[]> transfers = new ArrayList<>();
        byte nextReadValue;

        @Override
        public void transfer(byte[] tx, byte[] rx, int length) {
            transfers.add(new byte[] {tx[0], tx[1]});
            rx[0] = 0;
            rx[1] = nextReadValue;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void readAddressShiftsLeftAndSetsDirectionBit() {
        // VersionReg is 0x37. (0x37 << 1) & 0x7E = 0x6E, plus 0x80 = 0xEE.
        assertEquals((byte) 0xEE, Register.VERSION.readAddressByte());
        assertEquals((byte) 0x6E, Register.VERSION.writeAddressByte());
    }

    @Test
    void addressEncodingMatchesTheVerifiedProbeForEveryRegister() {
        for (Register register : Register.values()) {
            int address = register.address();
            assertEquals((byte) (((address << 1) & 0x7E) | 0x80), register.readAddressByte(),
                    register + " read address");
            assertEquals((byte) ((address << 1) & 0x7E), register.writeAddressByte(),
                    register + " write address");
        }
    }

    @Test
    void addressBitsSixToOneCarryTheRegisterAndBitZeroIsAlwaysClear() {
        for (Register register : Register.values()) {
            int read = register.readAddressByte() & 0xFF;
            assertEquals(0, read & 0x01, register + " must leave bit 0 clear");
            assertEquals(register.address(), (read & 0x7E) >> 1, register + " round trip");
        }
    }

    @Test
    void readClocksAddressThenZeroAndTakesTheSecondByte() throws NfcException {
        RecordingLink link = new RecordingLink();
        link.nextReadValue = (byte) 0x92;
        Rc522Driver driver = new Rc522Driver(link, new VirtualTimebase(), ProtocolTrace.none());

        int value = driver.read(Register.VERSION);

        assertEquals(0x92, value, "the reply arrives in the second byte slot");
        assertEquals(1, link.transfers.size());
        assertArrayEquals(new byte[] {(byte) 0xEE, 0x00}, link.transfers.get(0));
    }

    @Test
    void writeClocksAddressThenValue() throws NfcException {
        RecordingLink link = new RecordingLink();
        Rc522Driver driver = new Rc522Driver(link, new VirtualTimebase(), ProtocolTrace.none());

        driver.write(Register.TX_CONTROL, 0x83);

        assertArrayEquals(new byte[] {0x28, (byte) 0x83}, link.transfers.get(0));
    }

    @Test
    void versionReadsBackFromTheSimulatedChip() throws NfcException {
        SimulatedRc522 chip = new SimulatedRc522();
        Rc522Driver driver = new Rc522Driver(chip, new VirtualTimebase(), ProtocolTrace.none());

        assertEquals(SimulatedRc522.VERSION_MFRC522_V2, driver.version());
    }

    @Test
    void everyRegisterAddressIsUnique() {
        List<Integer> seen = new ArrayList<>();
        for (Register register : Register.values()) {
            assertTrue(seen.add(register.address()));
            assertEquals(1, seen.stream().filter(a -> a == register.address()).count(),
                    register + " has a duplicate address");
        }
    }
}
