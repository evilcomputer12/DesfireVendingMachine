package com.midnightbrewer.diag;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiMode;

/**
 * A DISPOSABLE wiring test. Reads the RC522's version register and stops.
 *
 * <p><strong>This is deliberately ugly.</strong> It is one flat method with no
 * abstractions, because its only job is to answer a single question before you
 * write any real code:
 *
 * <blockquote><em>Is the reader wired correctly and talking to me?</em></blockquote>
 *
 * <p>Keeping the wiring question separate from the code question is worth a
 * whole class. When your own driver misbehaves in Chapter 2 of the main book,
 * you can run this and know instantly whether to blame the wiring or the code.
 *
 * <p>You will write the proper version of this in Chapters 1--2, with a
 * testable seam and a real driver. Delete this file when you get there --- or
 * keep it as a field diagnostic, which is what it is good for.
 *
 * <pre>
 *   java -cp "target/classes:target/dependency/*" com.midnightbrewer.diag.SpiProbe
 * </pre>
 */
public final class SpiProbe {

    /** MFRC522 VersionReg. Answers 0x91 (v1.0) or 0x92 (v2.0). */
    private static final int VERSION_REG = 0x37;

    /** MFRC522 TxControlReg -- read to prove writes land too. */
    private static final int TX_CONTROL_REG = 0x14;

    private SpiProbe() {
    }

    public static void main(String[] args) {
        System.out.println("=== RC522 wiring probe ===");
        System.out.println("Opening SPI bus 0, chip-select 0, 1 MHz, mode 0...");

        Context pi4j = Pi4J.newAutoContext();
        try (Spi spi = pi4j.create(Spi.newConfigBuilder(pi4j)
                .id("rc522-probe")
                .address(0)              // CE0
                .baud(1_000_000)         // 1 MHz -- reliable over jumper wires
                .mode(SpiMode.MODE_0)    // CPOL=0, CPHA=0, per the datasheet
                .build())) {

            int version = readRegister(spi, VERSION_REG);
            int txControl = readRegister(spi, TX_CONTROL_REG);

            System.out.printf("VersionReg   (0x37) = 0x%02X%n", version);
            System.out.printf("TxControlReg (0x14) = 0x%02X%n", txControl);
            System.out.println();

            diagnose(version);

        } catch (Exception e) {
            System.out.println();
            System.out.println("  FAILED to open SPI: " + e.getMessage());
            System.out.println();
            System.out.println("  Most likely causes, in order:");
            System.out.println("    1. SPI not enabled       -> sudo raspi-config, Interface Options");
            System.out.println("    2. Not in the spi group  -> sudo usermod -aG spi,gpio $USER");
            System.out.println("       (then log out and back in -- groups only apply at login)");
            System.out.println("    3. Running on a laptop   -> this needs a real Raspberry Pi");
            System.out.println();
            System.out.println("  See section 12.4 of the setup book.");
        } finally {
            pi4j.shutdown();
        }
    }

    /**
     * One SPI register read, done the hard way.
     *
     * <p>The MFRC522 wants an address byte first, then one dummy byte during
     * which it shifts the answer back. The address byte is encoded:
     * bit 7 set = read, bits 6..1 = the register number, bit 0 = 0.
     */
    private static int readRegister(Spi spi, int register) {
        byte[] tx = { (byte) (((register << 1) & 0x7E) | 0x80), 0x00 };
        byte[] rx = new byte[2];
        spi.transfer(tx, 0, rx, 0, 2);
        return rx[1] & 0xFF;     // & 0xFF: Java bytes are signed
    }

    /** Turns the version byte into an actionable message. */
    private static void diagnose(int version) {
        switch (version) {
            case 0x91:
            case 0x92:
                System.out.println("  -> WIRING OK. The RC522 is alive and talking.");
                System.out.println("     You are ready for Chapter 1 of the main book.");
                break;

            case 0x00:
                System.out.println("  -> MISO is stuck LOW. The chip is not driving the line.");
                System.out.println("     Check, in this order:");
                System.out.println("       * 3.3V and GND actually connected (NOT 5V -- that kills it)");
                System.out.println("       * MISO  -> Pi pin 21 (BCM 9)");
                System.out.println("       * SDA   -> Pi pin 24 (BCM 8, CE0)   <- often mis-wired");
                break;

            case 0xFF:
                System.out.println("  -> MISO is FLOATING. Nothing is connected to it.");
                System.out.println("     Check MISO -> Pi pin 21 (BCM 9).");
                System.out.println("     If it is connected, suspect a dead module.");
                break;

            default:
                System.out.printf("  -> Unexpected value 0x%02X.%n", version);
                System.out.println("     Usually noise. Try SHORTER jumper wires --");
                System.out.println("     20 cm of loose wire at 1 MHz is genuinely unreliable.");
                System.out.println("     Run this probe a few times: if the value CHANGES");
                System.out.println("     between runs, it is definitely a wiring problem.");
        }
    }
}
