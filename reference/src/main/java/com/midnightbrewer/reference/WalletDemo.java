package com.midnightbrewer.reference;

import com.midnightbrewer.reference.desfire.DesfireApduChannel;
import com.midnightbrewer.reference.desfire.DesfireCard;
import com.midnightbrewer.reference.desfire.DesfireStatus;
import com.midnightbrewer.reference.desfire.WalletProfile;
import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.diag.TimingTransceiver;
import com.midnightbrewer.reference.error.DesfireStatusException;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.iso14443.ActivatedCard;
import com.midnightbrewer.reference.iso14443.Rc522IsoDepTransceiver;
import com.midnightbrewer.reference.pcd.Rc522Driver;
import com.midnightbrewer.reference.pcd.Register;
import com.midnightbrewer.reference.spi.Pi4jSpiLink;
import com.midnightbrewer.reference.util.Timebase;

/**
 * End-to-end wallet demo against a real DESFire card.
 *
 * <p>Runs the full stack the same way {@link ReferenceMain} brings the reader
 * up -- SPI, registers, antenna, anticollision, SELECT, RATS -- and then drives
 * the DESFire wallet:
 *
 * <ol>
 *   <li>select the wallet application (AID {@code 0x010203});</li>
 *   <li>if it is not there ({@code 0xA0}), provision the card: create the
 *       application, personalise its keys, and create a value file with an
 *       opening balance (2500 cents);</li>
 *   <li>authenticate with the application user key;</li>
 *   <li>read the balance;</li>
 *   <li>debit a small amount and CommitTransaction;</li>
 *   <li>read the balance back and print before / after.</li>
 * </ol>
 *
 * <p>A debit is only real once CommitTransaction returns {@code 0x00}: an
 * uncommitted debit is discarded by the card when it leaves the field. This
 * demo therefore prints "committed" only after that status, and re-reads the
 * balance from the card to prove it.
 *
 * <pre>{@code
 *   mvn -f reference/pom.xml exec:java \
 *       -Dexec.mainClass=com.midnightbrewer.reference.WalletDemo
 *   # options: --verbose (frame dumps), --debit=CENTS, --timeout=SECONDS
 * }</pre>
 *
 * <p><b>Safety:</b> there is no code path from here to FormatPICC. Provisioning
 * is additive -- it creates one application alongside anything else on the card
 * -- and the PICC master key is only ever used, never changed, so a card this
 * demo touches stays recoverable by other tools.
 */
public final class WalletDemo {

    private static final long POLL_INTERVAL_MS = 25L;
    private static final long DEFAULT_TIMEOUT_MS = 100L * POLL_INTERVAL_MS;

    private WalletDemo() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        ProtocolTrace trace = options.verbose ? ProtocolTrace.toStdout() : ProtocolTrace.none();

        System.out.println("DESFire wallet demo -- SPI0/CE0, " + Pi4jSpiLink.DEFAULT_BAUD_HZ
                + " Hz, RST on BCM " + Pi4jSpiLink.DEFAULT_RESET_GPIO);
        System.out.println();

        try (Pi4jSpiLink link = Pi4jSpiLink.openDefault()) {
            Rc522Driver driver = new Rc522Driver(link, Timebase.system(), trace);
            int exit = run(driver, options, trace, link);
            System.exit(exit);
        } catch (NfcException e) {
            System.out.println();
            System.out.println("FAILED: " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                System.out.println("  caused by: " + cause);
                cause = cause.getCause();
            }
            System.exit(1);
        }
    }

    private static int run(Rc522Driver driver, Options options, ProtocolTrace trace,
            Pi4jSpiLink link) throws NfcException {
        // ---- 1. reader --------------------------------------------------
        driver.initialise();
        int version = driver.version();
        int txControl = driver.read(Register.TX_CONTROL);
        System.out.printf("Reader VersionReg = 0x%02X, antenna %s%n",
                version, (txControl & 0x03) != 0 ? "ON" : "OFF");
        if (version == 0x00 || version == 0xFF) {
            System.out.println("FAILED: the RC522 is not answering on SPI.");
            return 1;
        }
        if ((txControl & 0x03) == 0) {
            System.out.println("FAILED: the antenna drivers did not come on.");
            return 1;
        }

        // ---- 2. find and activate a card --------------------------------
        System.out.println("Waiting for a card (" + options.timeoutMillis + " ms) ...");
        Rc522IsoDepTransceiver transceiver = new Rc522IsoDepTransceiver(driver);
        if (!waitForCard(transceiver, options.timeoutMillis)) {
            System.out.println("No card detected. Place one on the antenna and run again.");
            return 1;
        }
        ActivatedCard activated = transceiver.activate();
        System.out.println("Card activated: UID " + activated.uid()
                + ", SAK 0x" + Integer.toHexString(activated.sak() & 0xFF));
        System.out.println();

        // ---- 3. the wallet ----------------------------------------------
        // Wrap the transceiver so every APDU is timed. Nothing downstream
        // knows it is there -- see TimingTransceiver, it is a decorator.
        TimingTransceiver timed = new TimingTransceiver(transceiver);
        DesfireCard card = new DesfireCard(new DesfireApduChannel(timed),
                com.midnightbrewer.reference.desfire.RandomSource.secure(), trace);
        WalletProfile profile = WalletProfile.defaults();

        long spiBefore = link.transferCount();
        int exit = runWallet(card, profile, options.debitAmount);

        if (options.timing) {
            printTimingReport(timed, link.transferCount() - spiBefore);
        }

        // ---- 4. release --------------------------------------------------
        transceiver.deselect();
        transceiver.activator().halt();
        return exit;
    }

    /** A per-APDU timing table plus totals, printed after the sequence runs. */
    private static void printTimingReport(TimingTransceiver timed, long spiTransfers) {
        System.out.println();
        System.out.println("Transaction trace (per APDU):");
        System.out.printf("  %3s  %-24s %5s %5s %9s%n",
                "#", "command", "tx", "rx", "elapsed");
        System.out.println("  ---  ------------------------ ----- ----- ---------");
        for (TimingTransceiver.Exchange e : timed.exchanges()) {
            System.out.printf("  %3d  %-24s %4dB %4dB %7.1f ms%n",
                    e.sequence(), e.commandName(),
                    e.requestBytes(), e.responseBytes(), e.millis());
        }
        System.out.println("  ---  ------------------------ ----- ----- ---------");
        double total = timed.totalMillis();
        int apdus = timed.exchanges().size();
        System.out.printf("  %d APDUs, %.1f ms on the air (excludes card polling and JVM start)%n",
                apdus, total);
        // The "why is it slow" line. The card answers in single-digit ms; the
        // time goes on SPI syscalls, one per register touch and per FIFO byte.
        System.out.printf("  %d SPI transfers, one syscall each -> ~%.0f us/transfer of "
                        + "kernel+JNI overhead.%n",
                spiTransfers, spiTransfers == 0 ? 0 : total * 1000.0 / spiTransfers);
        System.out.println("  That fixed per-call cost, not the 1 MHz wire or the card, is the wall clock.");
        System.out.println("  Batching the FIFO reads/writes into one transfer each is the lever if it matters.");
    }

    /** The wallet sequence proper, split out so it reads as the steps it is. */
    private static int runWallet(DesfireCard card, WalletProfile profile, int debitAmount)
            throws NfcException {
        System.out.println("Step 1: select wallet application " + String.format("0x%06X", profile.aid()));
        boolean provisioned = selectOrProvision(card, profile);
        if (!provisioned) {
            return 1;
        }

        System.out.println("Step 2: authenticate with the application user key (key "
                + profile.userKeyNo() + ")");
        long t = System.nanoTime();
        card.selectApplication(profile.aid());
        card.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        System.out.println("  authenticated" + since(t));

        System.out.println("Step 3: read the balance");
        t = System.nanoTime();
        int before = readOrCreateValue(card, profile);
        System.out.println("  balance before: " + formatCents(before)
                + " (" + before + " cents)" + since(t));

        if (before < debitAmount) {
            System.out.println("  balance is below the debit amount; skipping the debit.");
            System.out.println();
            System.out.println("Done. Read-only balance check succeeded.");
            return 0;
        }

        System.out.println("Step 4: debit " + formatCents(debitAmount) + " and commit");
        t = System.nanoTime();
        card.debit(profile.fileNo(), debitAmount);
        System.out.println("  debit staged (not yet permanent)" + since(t));
        long tc = System.nanoTime();
        card.commitTransaction();
        System.out.println("  CommitTransaction returned OK -- the debit is now permanent"
                + since(tc));

        System.out.println("Step 5: read the balance back");
        t = System.nanoTime();
        int after = card.getValue(profile.fileNo());
        System.out.println("  balance after:  " + formatCents(after)
                + " (" + after + " cents)" + since(t));
        System.out.println();

        int expected = before - debitAmount;
        if (after == expected) {
            System.out.println("SUCCESS: " + formatCents(before) + " - " + formatCents(debitAmount)
                    + " = " + formatCents(after) + ", committed and verified on the card.");
            return 0;
        }
        System.out.println("WARNING: expected " + formatCents(expected) + " but read "
                + formatCents(after) + " -- the commit did not take as expected.");
        return 1;
    }

    /**
     * Reads the balance, creating the value file first if the application
     * exists but the file does not.
     *
     * <p>This is the state a card the STM32 firmware set up is in: the
     * application {@code 0x010203} is present with its keys, but the firmware
     * created a standard <em>data</em> file at 0x02, not a <em>value</em> file.
     * The wallet lives in file 0x01 (matching flutter_topup), so on a
     * firmware-provisioned card that file is missing and GetValue returns
     * {@code FILE_NOT_FOUND}.
     *
     * <p>Creating a file needs the application master key (key 0), while
     * reading the value needs the user key (key 2), so this re-authenticates
     * across the two. It mirrors what the Flutter top-up app does when it meets
     * the same half-provisioned card.
     */
    private static int readOrCreateValue(DesfireCard card, WalletProfile profile)
            throws NfcException {
        try {
            return card.getValue(profile.fileNo());
        } catch (DesfireStatusException e) {
            if (!e.is(DesfireStatus.FILE_NOT_FOUND)) {
                throw e;
            }
        }

        System.out.println("  value file " + profile.fileNo()
                + " not present -- creating it with an opening balance of "
                + formatCents(profile.initialBalance()));
        // CreateFile is a master-key operation; the user key cannot do it.
        card.selectApplication(profile.aid());
        card.authenticateEv2First(0, profile.appMasterKey());
        card.createValueFile(com.midnightbrewer.reference.desfire.ValueFileSettings
                .builder(profile.fileNo())
                .accessRights(profile.accessRights())
                .lowerLimit(profile.lowerLimit())
                .upperLimit(profile.upperLimit())
                .initialValue(profile.initialBalance())
                .build());

        // Back to the user key for the read and the debit that follow.
        card.selectApplication(profile.aid());
        card.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        return card.getValue(profile.fileNo());
    }

    /**
     * Selects the wallet application, provisioning the card first if it is not
     * there. Returns false if selection failed for a reason other than "not
     * provisioned".
     */
    private static boolean selectOrProvision(DesfireCard card, WalletProfile profile)
            throws NfcException {
        try {
            card.selectApplication(profile.aid());
            System.out.println("  application present");
            return true;
        } catch (DesfireStatusException e) {
            if (!e.is(DesfireStatus.APPLICATION_NOT_FOUND)) {
                throw e;
            }
        }
        System.out.println("  application not found -- provisioning this card");
        System.out.println("  (creating the app, personalising keys, and creating the value file"
                + " with an opening balance of " + formatCents(profile.initialBalance()) + ")");
        card.provisionValueWallet(profile);
        System.out.println("  provisioning complete");
        return true;
    }

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

    /** Wall-clock since {@code startNanos}, formatted as {@code "  (12.3 ms)"}. */
    private static String since(long startNanos) {
        return String.format("  (%.1f ms)", (System.nanoTime() - startNanos) / 1_000_000.0);
    }

    /** Formats a cent amount as a euro-style string: 2150 -> "21.50". */
    static String formatCents(int cents) {
        boolean negative = cents < 0;
        int abs = Math.abs(cents);
        return (negative ? "-" : "") + (abs / 100) + "." + String.format("%02d", abs % 100);
    }

    private static final class Options {
        private final boolean verbose;
        private final boolean timing;
        private final long timeoutMillis;
        private final int debitAmount;

        private Options(boolean verbose, boolean timing, long timeoutMillis, int debitAmount) {
            this.verbose = verbose;
            this.timing = timing;
            this.timeoutMillis = timeoutMillis;
            this.debitAmount = debitAmount;
        }

        static Options parse(String[] args) {
            boolean verbose = false;
            boolean timing = false;
            long timeout = DEFAULT_TIMEOUT_MS;
            int debit = 350;
            for (String arg : args) {
                if (arg.equals("--verbose")) {
                    verbose = true;
                } else if (arg.equals("--timing")) {
                    timing = true;
                } else if (arg.startsWith("--timeout=")) {
                    timeout = Long.parseLong(arg.substring("--timeout=".length())) * 1000L;
                } else if (arg.startsWith("--debit=")) {
                    debit = Integer.parseInt(arg.substring("--debit=".length()));
                } else {
                    System.out.println("Unknown option: " + arg);
                    System.out.println("Usage: WalletDemo [--verbose] [--timing]"
                            + " [--timeout=SECONDS] [--debit=CENTS]");
                }
            }
            return new Options(verbose, timing, timeout, debit);
        }
    }
}
