package com.midnightbrewer.reference;

import com.midnightbrewer.reference.desfire.DesfireApduChannel;
import com.midnightbrewer.reference.desfire.DesfireVersion;
import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.iso14443.ActivatedCard;
import com.midnightbrewer.reference.iso14443.Rc522IsoDepTransceiver;
import com.midnightbrewer.reference.pcd.Rc522Driver;
import com.midnightbrewer.reference.pcd.Register;
import com.midnightbrewer.reference.spi.Pi4jSpiLink;
import com.midnightbrewer.reference.util.Hex;
import com.midnightbrewer.reference.util.Timebase;

/**
 * Hardware bring-up: open the reader, find a card, and talk to it.
 *
 * <p>Runs the whole stack end to end -- SPI, registers, antenna, ISO 14443-3
 * activation through both cascade levels, RATS, then a DESFire GetVersion with
 * its {@code 0xAF} continuation frames. GetVersion is the right first command
 * because it needs no keys, no authentication and no application, so it
 * exercises everything while being unable to change anything on the card.
 *
 * <p>Each stage prints what it found and stops at the first failure, because
 * during bring-up the first failure is the only informative one.
 *
 * <pre>{@code
 *   mvn -f reference/pom.xml package
 *   mvn -f reference/pom.xml exec:java
 *   mvn -f reference/pom.xml exec:java -Dexec.args="--quiet --timeout=10"
 * }</pre>
 */
public final class ReferenceMain {

    /** Gap between polls while waiting for a card, from the C's {@code wait_for_card}. */
    private static final long POLL_INTERVAL_MS = 25L;

    /** Default time to wait for a card. The C polls 100 times at 25 ms. */
    private static final long DEFAULT_TIMEOUT_MS = 100L * POLL_INTERVAL_MS;

    private ReferenceMain() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);

        System.out.println("RC522 reference driver -- SPI0/CE0, "
                + Pi4jSpiLink.DEFAULT_BAUD_HZ + " Hz, mode 0, RST on BCM "
                + Pi4jSpiLink.DEFAULT_RESET_GPIO);
        System.out.println();

        ProtocolTrace trace = options.verbose ? ProtocolTrace.toStdout() : ProtocolTrace.none();

        try (Pi4jSpiLink link = Pi4jSpiLink.openDefault()) {
            Rc522Driver driver = new Rc522Driver(link, Timebase.system(), trace);
            int exitCode = run(driver, options);
            System.exit(exitCode);
        } catch (NfcException e) {
            System.out.println();
            System.out.println("FAILED: " + e.getMessage());
            printCause(e);
            System.exit(1);
        }
    }

    private static int run(Rc522Driver driver, Options options) throws NfcException {
        // ---- 1. reader ----------------------------------------------------
        driver.initialise();

        int version = driver.version();
        int txControl = driver.read(Register.TX_CONTROL);

        System.out.printf("VersionReg   (0x37) = 0x%02X  %s%n", version, describeVersion(version));
        System.out.printf("TxControlReg (0x14) = 0x%02X  antenna %s%n",
                txControl, (txControl & 0x03) != 0 ? "ON" : "OFF -- no card will be visible");
        System.out.println();

        if (version == 0x00 || version == 0xFF) {
            System.out.println("FAILED: the RC522 is not answering on SPI.");
            System.out.println("  Check 3.3 V at the module, a common ground, MISO on pin 21,");
            System.out.println("  and that RST (BCM 25 / pin 22) is high.");
            return 1;
        }
        if ((txControl & 0x03) == 0) {
            System.out.println("FAILED: the antenna drivers did not come on.");
            return 1;
        }

        // ---- 2. find a card ------------------------------------------------
        System.out.println("Waiting for a card (" + options.timeoutMillis + " ms) ...");

        Rc522IsoDepTransceiver transceiver = new Rc522IsoDepTransceiver(driver);
        if (!waitForCard(transceiver, options.timeoutMillis)) {
            System.out.println("No card detected. Place one on the antenna and run again.");
            return 1;
        }
        System.out.println("Card present.");
        System.out.println();

        // ---- 3. activate ---------------------------------------------------
        // Anticollision at both cascade levels, SELECT, then RATS. A DESFire has
        // a 7-byte UID, so its level 1 SAK sets bit 0x04 and level 2 runs.
        ActivatedCard card = transceiver.activate();

        System.out.println("Activated:");
        System.out.println("  UID   : " + card.uid() + "  (" + card.uid().length() + " bytes)");
        System.out.printf("  SAK   : 0x%02X  %s%n", card.sak(),
                card.selection().supportsIso14443Part4()
                        ? "ISO 14443-4 supported"
                        : "card does not claim ISO 14443-4");
        System.out.println("  ATS   : " + card.answerToSelect());
        System.out.println("  frame : " + card.frameSize());
        System.out.println();

        // ---- 4. DESFire GetVersion ------------------------------------------
        System.out.println("DESFire GetVersion (0x60 with 0xAF continuations):");
        DesfireApduChannel desfire = new DesfireApduChannel(transceiver);
        DesfireVersion cardVersion = desfire.getVersion();
        System.out.println(cardVersion.toReport());
        System.out.println();

        // ---- 5. release ------------------------------------------------------
        // Deselect politely, then HALT so the card stops answering REQA and a
        // rerun does not immediately re-activate the same one.
        transceiver.deselect();
        transceiver.activator().halt();

        System.out.println("Done. Full stack verified: SPI -> registers -> antenna -> "
                + "anticollision -> SELECT -> RATS -> ISO-DEP -> DESFire.");
        return 0;
    }

    /**
     * Polls until a card answers REQA or WUPA, or the timeout expires.
     *
     * <p>Same shape as the C's {@code wait_for_card}: a bounded loop with a
     * 25 ms gap, so the reader is not hammering the field continuously.
     */
    private static boolean waitForCard(Rc522IsoDepTransceiver transceiver, long timeoutMillis)
            throws NfcException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (transceiver.isCardPresent()) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String describeVersion(int version) {
        switch (version) {
            case 0x91: return "MFRC522 v1.0";
            case 0x92: return "MFRC522 v2.0";
            case 0x88: return "clone chip -- usually works, expect quirks";
            case 0x00: return "nothing is driving MISO";
            case 0xFF: return "MISO stuck high, probably floating";
            default: return "unexpected silicon revision";
        }
    }

    private static void printCause(Throwable error) {
        Throwable cause = error.getCause();
        while (cause != null) {
            System.out.println("  caused by: " + cause);
            cause = cause.getCause();
        }
    }

    /** Command line options, parsed once so the rest of the class takes values. */
    private static final class Options {

        private final boolean verbose;
        private final long timeoutMillis;

        private Options(boolean verbose, long timeoutMillis) {
            this.verbose = verbose;
            this.timeoutMillis = timeoutMillis;
        }

        static Options parse(String[] args) {
            boolean verbose = true;
            long timeout = DEFAULT_TIMEOUT_MS;
            for (String arg : args) {
                if (arg.equals("--quiet")) {
                    verbose = false;
                } else if (arg.startsWith("--timeout=")) {
                    timeout = Long.parseLong(arg.substring("--timeout=".length())) * 1000L;
                } else {
                    System.out.println("Unknown option: " + arg);
                    System.out.println("Usage: ReferenceMain [--quiet] [--timeout=SECONDS]");
                }
            }
            return new Options(verbose, timeout);
        }
    }
}
