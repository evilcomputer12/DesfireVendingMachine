package com.midnightbrewer.probe;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiChipSelect;
import com.pi4j.io.spi.SpiMode;

/**
 * Wiring check: talk to the RC522 over SPI and read its version register.
 *
 * <p><strong>This class is deliberately bad code, and that is the point.</strong>
 *
 * <p>It is one flat {@code main} with hardcoded constants, magic numbers, no
 * error types, no seams and no way to test it without the physical board
 * plugged in. It is, in other words, a fairly faithful Java transcription of
 * how the equivalent logic looks in {@code RC522.c} -- procedural code that
 * works and that you cannot take apart.
 *
 * <p>Its only job is to prove the toolchain: that Pi4J loads its native
 * libraries on this Pi, that {@code /dev/spidev0.0} opens, and that the chip
 * on the other end answers. Once it prints a version byte, the hardware
 * question is closed and every later failure is a software failure -- which
 * is worth a great deal when you are debugging RF protocols.
 *
 * <p>After that, this file becomes your worked example of what to refactor.
 * {@code docs/OOP-LEARNING-PATH.md} takes it apart step by step. Do not build
 * on this class; build the thing that replaces it.
 *
 * <p>Run it with:
 * <pre>{@code
 *   mvn -q compile
 *   mvn -q exec:java -Dexec.mainClass=com.midnightbrewer.probe.Rc522Probe
 * }</pre>
 */
public final class Rc522Probe {

    // --- RC522 registers (see section 9.2 of the MFRC522 datasheet) ---
    private static final byte COMMAND_REG = 0x01;
    private static final byte VERSION_REG = 0x37;
    private static final byte TX_CONTROL_REG = 0x14;

    /** PCD_SOFTRESET. */
    private static final byte CMD_SOFT_RESET = 0x0F;

    /** BCM numbering. Physical pin 22. */
    private static final int RST_GPIO = 25;

    private Rc522Probe() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("RC522 probe -- SPI0 CS0, 1 MHz, mode 0, RST on BCM " + RST_GPIO);

        Context pi4j = Pi4J.newAutoContext();
        try {
            // The RC522 holds itself in reset while RST is low, so this has to
            // go high before any register will answer.
            DigitalOutput rst = pi4j.create(
                    DigitalOutput.newConfigBuilder(pi4j)
                            .id("rc522-rst")
                            .name("RC522 reset")
                            .address(RST_GPIO)
                            .initial(DigitalState.HIGH)
                            .shutdown(DigitalState.LOW)
                            .build());
            rst.state(DigitalState.HIGH);
            Thread.sleep(50);

            Spi spi = pi4j.create(
                    Spi.newConfigBuilder(pi4j)
                            .id("rc522-spi")
                            .name("RC522")
                            .bus(SpiBus.BUS_0)
                            .chipSelect(SpiChipSelect.CS_0)
                            .mode(SpiMode.MODE_0)
                            .baud(1_000_000)
                            .build());

            writeRegister(spi, COMMAND_REG, CMD_SOFT_RESET);
            Thread.sleep(50);

            int version = readRegister(spi, VERSION_REG);
            int txControl = readRegister(spi, TX_CONTROL_REG);

            System.out.printf("VersionReg   (0x37) = 0x%02X%n", version);
            System.out.printf("TxControlReg (0x14) = 0x%02X%n", txControl);
            System.out.println();
            System.out.println(interpret(version));

            spi.close();
        } finally {
            pi4j.shutdown();
        }
    }

    /**
     * RC522 SPI address byte: the register number is shifted left one bit,
     * masked to bits 6..1, and bit 7 selects direction -- 1 for read.
     * A read clocks out two bytes and the answer arrives in the second.
     */
    private static int readRegister(Spi spi, byte register) {
        byte address = (byte) (((register << 1) & 0x7E) | 0x80);
        byte[] tx = {address, 0x00};
        byte[] rx = new byte[2];
        spi.transfer(tx, rx, 2);
        return rx[1] & 0xFF;
    }

    private static void writeRegister(Spi spi, byte register, byte value) {
        byte address = (byte) ((register << 1) & 0x7E);
        byte[] tx = {address, value};
        byte[] rx = new byte[2];
        spi.transfer(tx, rx, 2);
    }

    private static String interpret(int version) {
        switch (version) {
            case 0x91:
                return "OK -- MFRC522 v1.0 responding. Wiring is good.";
            case 0x92:
                return "OK -- MFRC522 v2.0 responding. Wiring is good.";
            case 0x88:
                return "A clone chip answered (0x88). Usually works; expect quirks.";
            case 0x00:
                return "0x00 -- nothing is driving MISO.\n"
                        + "  Check: 3.3V present? GND common? MISO on pin 21?\n"
                        + "  Check: RST (pin 22) actually high?";
            case 0xFF:
                return "0xFF -- MISO stuck high, which usually means it is floating.\n"
                        + "  Check: MISO wire seated? Correct CS (pin 24 / CE0)?";
            default:
                return String.format(
                        "Unexpected 0x%02X. The bus is doing something, so wiring is\n"
                        + "  probably close -- suspect SPI mode, clock speed, or a\n"
                        + "  swapped MOSI/MISO pair.", version);
        }
    }
}
