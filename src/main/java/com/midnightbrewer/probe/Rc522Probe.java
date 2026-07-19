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

    // Init sequence, lifted verbatim from MFRC522_Init() in RC522.c.
    private static final byte MODE_REG = 0x11;
    private static final byte TX_AUTO_REG = 0x15;
    private static final byte RF_CFG_REG = 0x26;
    private static final byte T_MODE_REG = 0x2A;
    private static final byte T_PRESCALER_REG = 0x2B;
    private static final byte T_RELOAD_REG_H = 0x2C;
    private static final byte T_RELOAD_REG_L = 0x2D;

    // Card detection (REQA).
    private static final byte COMM_IEN_REG = 0x02;
    private static final byte COMM_IRQ_REG = 0x04;
    private static final byte ERROR_REG = 0x06;
    private static final byte FIFO_DATA_REG = 0x09;
    private static final byte FIFO_LEVEL_REG = 0x0A;
    private static final byte CONTROL_REG = 0x0C;
    private static final byte BIT_FRAMING_REG = 0x0D;

    private static final byte PCD_IDLE = 0x00;
    private static final byte PCD_TRANSCEIVE = 0x0C;
    private static final byte PICC_REQIDL = 0x26;

    /** PCD_SOFTRESET. */
    private static final byte CMD_SOFT_RESET = 0x0F;

    /** BCM numbering. Physical pin 22. */
    private static final int RST_GPIO = 25;

    private Rc522Probe() {
    }

    public static void main(String[] args) throws Exception {
        boolean loopback = args.length > 0 && args[0].equals("--loopback");
        int baud = 1_000_000;
        for (String a : args) {
            if (a.startsWith("--baud=")) {
                baud = Integer.parseInt(a.substring(7));
            }
        }

        System.out.println("RC522 probe -- SPI0 CS0, " + baud + " Hz, mode 0, RST on BCM "
                + RST_GPIO + (loopback ? "  [LOOPBACK MODE]" : ""));

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
            System.out.println("RST pin reads back: " + rst.state()
                    + "  (must be HIGH, or the chip stays in reset)");

            Spi spi = pi4j.create(
                    Spi.newConfigBuilder(pi4j)
                            .id("rc522-spi")
                            .name("RC522")
                            .bus(SpiBus.BUS_0)
                            .chipSelect(SpiChipSelect.CS_0)
                            .mode(SpiMode.MODE_0)
                            .baud(baud)
                            .build());

            if (loopback) {
                runLoopback(spi);
                spi.close();
                return;
            }

            writeRegister(spi, COMMAND_REG, CMD_SOFT_RESET);
            Thread.sleep(50);

            int version = readRegister(spi, VERSION_REG);
            int txControl = readRegister(spi, TX_CONTROL_REG);

            System.out.printf("VersionReg   (0x37) = 0x%02X%n", version);
            System.out.printf("TxControlReg (0x14) = 0x%02X%n", txControl);
            System.out.println();
            System.out.println(interpret(version));

            // Bring the RF field up, exactly as MFRC522_Init() does.
            System.out.println();
            System.out.println("-- init + antenna --");
            initChip(spi);

            int txAfter = readRegister(spi, TX_CONTROL_REG);
            System.out.printf("TxControlReg after AntennaOn = 0x%02X%n", txAfter);
            if ((txAfter & 0x03) == 0x03) {
                System.out.println("RF FIELD ON -- Tx1RFEn and Tx2RFEn are both set.");
                System.out.println("  The reader is now energising cards placed on the antenna.");
            } else {
                System.out.println("RF field did NOT come on. Antenna drivers still disabled.");
                System.out.println("  Suspect the antenna coil connection on the module itself.");
            }

            // Detection is attempted repeatedly: one lucky read proves nothing,
            // and an intermittent RF stack is worse than a broken one.
            System.out.println();
            System.out.println("-- card detection (REQA x20) --");
            int hits = 0;
            byte[] lastAtqa = null;
            for (int i = 0; i < 20; i++) {
                byte[] atqa = requestA(spi);
                if (atqa != null) {
                    hits++;
                    lastAtqa = atqa;
                }
                Thread.sleep(30);
            }
            System.out.printf("detected %d/20 attempts%n", hits);
            if (lastAtqa != null) {
                System.out.println("ATQA = " + hex(lastAtqa) + describeAtqa(lastAtqa));
            }
            if (hits == 0) {
                System.out.println("No card answered. Either nothing is on the antenna,");
                System.out.println("  it is too far away, or it is not ISO-14443 Type A.");
            } else if (hits < 20) {
                System.out.println("INTERMITTENT -- reposition the card flat on the coil.");
            } else {
                System.out.println("Rock solid. Card is present and answering every time.");
            }

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

    /**
     * Sends a known pattern and checks whether it comes back.
     *
     * <p>Run this with the RC522 disconnected and a single jumper tying MOSI
     * (pin 19) directly to MISO (pin 21). It splits the problem cleanly in
     * two: if the pattern echoes, the Pi's SPI controller, both data lines and
     * the kernel driver are all fine, and the fault is the module or its
     * power. If it does not echo, the fault is on the Pi side and the module
     * is irrelevant.
     */
    private static void runLoopback(Spi spi) {
        byte[] tx = {(byte) 0xA5, 0x5A, (byte) 0xFF, 0x00, 0x0F, (byte) 0xF0};
        byte[] rx = new byte[tx.length];
        spi.transfer(tx, rx, tx.length);

        System.out.println("  sent: " + hex(tx));
        System.out.println("  recv: " + hex(rx));
        System.out.println();

        if (java.util.Arrays.equals(tx, rx)) {
            System.out.println("LOOPBACK PASS -- the Pi's SPI works and MISO reads correctly.");
            System.out.println("  So the fault is the RC522 module or its power, not the Pi.");
            System.out.println("  Check 3.3V at the module's own pin, and that its header is");
            System.out.println("  actually soldered rather than press-fitted.");
        } else {
            boolean allZero = true;
            for (byte b : rx) {
                if (b != 0) {
                    allZero = false;
                    break;
                }
            }
            System.out.println("LOOPBACK FAIL -- the Pi did not read back what it sent.");
            System.out.println(allZero
                    ? "  All zeros: the MOSI->MISO jumper is not making contact,\n"
                      + "  or it is on the wrong pins (MOSI=19, MISO=21)."
                    : "  Garbled rather than zero: that IS a signal-integrity symptom.\n"
                      + "  Retry with --baud=100000 and shorter wires.");
        }
    }

    /**
     * The init sequence from {@code MFRC522_Init()}, values unchanged.
     *
     * <p>The timer setup is the part worth understanding: the prescaler makes
     * one tick 0.5 ms and TReload = 600 gives roughly a 300 ms timeout. The C
     * carries a comment saying 15 ms was too short, because DESFire
     * non-volatile writes (CreateApplication, ChangeKey, WriteData) can take
     * 50-100 ms and the reader must not give up while the card is still busy
     * committing to EEPROM.
     */
    private static void initChip(Spi spi) throws InterruptedException {
        writeRegister(spi, COMMAND_REG, CMD_SOFT_RESET);
        Thread.sleep(50);

        writeRegister(spi, T_MODE_REG, (byte) 0x8D);
        writeRegister(spi, T_PRESCALER_REG, (byte) 0x3E);
        writeRegister(spi, T_RELOAD_REG_H, (byte) 2);    // TReload = 2*256 + 88 = 600
        writeRegister(spi, T_RELOAD_REG_L, (byte) 88);

        writeRegister(spi, TX_AUTO_REG, (byte) 0x40);    // force 100% ASK modulation
        writeRegister(spi, MODE_REG, (byte) 0x3D);       // CRC preset 0x6363
        writeRegister(spi, RF_CFG_REG, (byte) 0x08);     // lower RX gain

        // AntennaOn(): read-modify-write, setting Tx1RFEn | Tx2RFEn.
        int current = readRegister(spi, TX_CONTROL_REG);
        writeRegister(spi, TX_CONTROL_REG, (byte) (current | 0x03));
    }

    /**
     * REQA: asks any card in the field to announce itself, and returns the
     * 2-byte ATQA. Port of {@code MFRC522_Request} + {@code MFRC522_ToCard}.
     *
     * @return the ATQA, or {@code null} if no card answered
     */
    private static byte[] requestA(Spi spi) {
        // 7-bit frame: REQA is a short frame, not a whole byte.
        writeRegister(spi, BIT_FRAMING_REG, (byte) 0x07);

        writeRegister(spi, COMM_IEN_REG, (byte) (0x77 | 0x80));
        clearBitMask(spi, COMM_IRQ_REG, (byte) 0x80);
        setBitMask(spi, FIFO_LEVEL_REG, (byte) 0x80);      // flush FIFO
        writeRegister(spi, COMMAND_REG, PCD_IDLE);

        writeRegister(spi, FIFO_DATA_REG, PICC_REQIDL);
        writeRegister(spi, COMMAND_REG, PCD_TRANSCEIVE);
        setBitMask(spi, BIT_FRAMING_REG, (byte) 0x80);     // StartSend

        /*
         * The C spins a 2,000,000-iteration counter as a backstop, sized for
         * ~4 us per iteration on the STM32. Here every iteration is an SPI
         * syscall costing tens of microseconds, so that count would run for
         * minutes. Iteration counts do not port; use a wall-clock deadline
         * that is comfortably longer than the RC522's own ~300 ms timer.
         */
        int n;
        long deadline = System.nanoTime() + 500_000_000L;
        do {
            n = readRegister(spi, COMM_IRQ_REG);
        } while (System.nanoTime() < deadline && (n & 0x01) == 0 && (n & 0x30) == 0);

        clearBitMask(spi, BIT_FRAMING_REG, (byte) 0x80);

        if ((readRegister(spi, ERROR_REG) & 0x1B) != 0) {
            return null;                                   // CRC / collision / protocol error
        }
        if ((n & 0x01) != 0) {
            return null;                                   // TimerIRq: nobody answered
        }

        int fifo = readRegister(spi, FIFO_LEVEL_REG);
        int lastBits = readRegister(spi, CONTROL_REG) & 0x07;
        int bits = lastBits != 0 ? (fifo - 1) * 8 + lastBits : fifo * 8;
        if (bits != 0x10) {
            return null;                                   // ATQA must be exactly 16 bits
        }

        byte[] atqa = new byte[2];
        for (int i = 0; i < 2; i++) {
            atqa[i] = (byte) readRegister(spi, FIFO_DATA_REG);
        }
        return atqa;
    }

    /** ATQA bit 6 of byte 0 signals a UID longer than 4 bytes. */
    private static String describeAtqa(byte[] atqa) {
        int b0 = atqa[0] & 0xFF;
        if (b0 == 0x44) {
            return "   (7-byte UID, ISO-14443-4 capable -- consistent with DESFire)";
        }
        if (b0 == 0x04) {
            return "   (4-byte UID -- looks like MIFARE Classic, not DESFire)";
        }
        return "";
    }

    private static void setBitMask(Spi spi, byte register, byte mask) {
        writeRegister(spi, register, (byte) (readRegister(spi, register) | mask));
    }

    private static void clearBitMask(Spi spi, byte register, byte mask) {
        writeRegister(spi, register, (byte) (readRegister(spi, register) & ~mask));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X ", x));
        }
        return sb.toString().trim();
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
