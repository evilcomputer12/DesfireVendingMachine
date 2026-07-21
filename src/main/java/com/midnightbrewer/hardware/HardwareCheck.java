package com.midnightbrewer.hardware;

/**
 * Runs YOUR driver against the real RC522 on the Pi.
 *
 * The whole point: this is the exact same {@link Rc522Driver} your unit test
 * exercises with a {@link FakeSpiLink}. Here it gets a {@link Pi4jSpiLink}
 * instead -- one part swapped -- and reads the real chip. The driver code did
 * not change. That is the interface (the "socket") paying off.
 */
public final class HardwareCheck {

    private HardwareCheck() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Opening the reader (your Pi4jSpiLink)...");

        // try-with-resources: link.close() runs automatically at the end,
        // even if something throws -- the reason SpiLink extends AutoCloseable.
        try (Pi4jSpiLink link = new Pi4jSpiLink()) {

            // The swap. Same driver, real bus this time.
            Rc522Driver driver = new Rc522Driver(link);

            driver.softReset();

            int version = driver.version();
            System.out.printf("version()          = 0x%02X%n", version);
            if (version == 0x91 || version == 0x92) {
                System.out.println("  -> a real MFRC522 answered. Your driver works on silicon.");
            } else {
                System.out.println("  -> unexpected; check wiring / RST.");
            }

            // Exercise more of your driver: antennaOn() is readRegister +
            // setBitMask (read-modify-write). TxControlReg should go 0x80 -> 0x83.
            driver.antennaOn();
            int txControl = driver.readRegister(0x14);
            System.out.printf("readRegister(0x14) = 0x%02X  (0x83 = RF field on)%n", txControl);
        }

        System.out.println("Reader closed.");
    }
}
