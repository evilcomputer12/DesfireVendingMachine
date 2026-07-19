package com.midnightbrewer.reference.pcd;

import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.support.SimulatedRc522;
import com.midnightbrewer.reference.support.VirtualTimebase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SetBitMask} and {@code ClearBitMask}, and the antenna control built on
 * them.
 *
 * <p>The antenna case is the one that matters in practice. After a reset
 * {@code TxControlReg} reads {@code 0x80} -- both transmitter drivers off -- and
 * a reader that never turns them on sees no card at any distance while looking
 * perfectly healthy on SPI. The simulated chip starts in that same state, so
 * these tests fail if the driver stops turning the antenna on.
 */
class BitMaskTest {

    private SimulatedRc522 chip;
    private Rc522Driver driver;

    @BeforeEach
    void setUp() {
        chip = new SimulatedRc522();
        driver = new Rc522Driver(chip, new VirtualTimebase(), ProtocolTrace.none());
    }

    @Test
    void setBitMaskOnlyAddsBits() throws NfcException {
        driver.write(Register.MODE, 0b0000_1100);
        driver.setBitMask(Register.MODE, 0b0011_0000);

        assertEquals(0b0011_1100, driver.read(Register.MODE));
    }

    @Test
    void clearBitMaskOnlyRemovesBits() throws NfcException {
        driver.write(Register.MODE, 0b0011_1100);
        driver.clearBitMask(Register.MODE, 0b0011_0000);

        assertEquals(0b0000_1100, driver.read(Register.MODE));
    }

    @Test
    void clearBitMaskOnBitsAlreadyClearChangesNothing() throws NfcException {
        driver.write(Register.MODE, 0b0000_1100);
        driver.clearBitMask(Register.MODE, 0b0011_0000);

        assertEquals(0b0000_1100, driver.read(Register.MODE));
    }

    @Test
    void antennaIsOffAfterResetAndMustBeTurnedOnExplicitly() throws NfcException {
        driver.softReset();

        assertEquals(0x80, driver.read(Register.TX_CONTROL),
                "TxControlReg reads 0x80 after reset: both drivers off");
        assertFalse(driver.isAntennaOn());
    }

    @Test
    void antennaOnSetsBothDriverBitsWithoutDisturbingTheRest() throws NfcException {
        driver.softReset();
        driver.antennaOn();

        assertEquals(0x83, driver.read(Register.TX_CONTROL),
                "0x80 preserved, 0x03 added -- this is the value seen on real hardware");
        assertTrue(driver.isAntennaOn());
        assertTrue(chip.isAntennaOn());
    }

    @Test
    void antennaOffClearsOnlyTheDriverBits() throws NfcException {
        driver.softReset();
        driver.antennaOn();
        driver.antennaOff();

        assertEquals(0x80, driver.read(Register.TX_CONTROL));
        assertFalse(driver.isAntennaOn());
    }

    @Test
    void initialiseLeavesTheAntennaOnAndTheTunedRegistersSet() throws NfcException {
        driver.initialise();

        assertTrue(driver.isAntennaOn(), "init must end with the field up");
        assertEquals(0x8D, driver.read(Register.TIMER_MODE));
        assertEquals(0x3E, driver.read(Register.TIMER_PRESCALER));
        assertEquals(2, driver.read(Register.TIMER_RELOAD_HIGH), "TReload high byte: 600 total");
        assertEquals(88, driver.read(Register.TIMER_RELOAD_LOW));
        assertEquals(0x40, driver.read(Register.TX_AUTO), "forced 100% ASK modulation");
        assertEquals(0x3D, driver.read(Register.MODE), "CRC preset 0x6363");
        assertEquals(0x08, driver.read(Register.RF_CONFIG), "lowered RX gain");
    }

    @Test
    void nvWriteTimeoutInstallsTheLongerReload() throws NfcException {
        driver.applyTimeout(ResponseTimeout.NV_WRITE);

        assertEquals(15, driver.read(Register.TIMER_RELOAD_HIGH));
        assertEquals(159, driver.read(Register.TIMER_RELOAD_LOW));
        assertEquals(2000, ResponseTimeout.NV_WRITE.approximateMillis());
        assertEquals(300, ResponseTimeout.DEFAULT.approximateMillis());
    }
}
