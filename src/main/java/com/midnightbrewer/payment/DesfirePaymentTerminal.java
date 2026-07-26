package com.midnightbrewer.payment;

import com.midnightbrewer.card.AuthenticationFailedException;
import com.midnightbrewer.card.CardCommunicationException;
import com.midnightbrewer.card.CardException;
import com.midnightbrewer.card.InsufficientFundsException;
import com.midnightbrewer.model.Drink;

import com.midnightbrewer.hardware.ActivatedCard;
import com.midnightbrewer.hardware.DesfireCard;
import com.midnightbrewer.hardware.IsoDepChannel;
import com.midnightbrewer.hardware.Pi4jSpiLink;
import com.midnightbrewer.hardware.RandomSource;
import com.midnightbrewer.hardware.Rc522Driver;
import com.midnightbrewer.hardware.Rc522Reader;
import com.midnightbrewer.hardware.SpiException;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The real {@link PaymentTerminal}: charges a DESFire card through the
 * hand-written {@code com.midnightbrewer.hardware} stack.
 *
 * <p>This is an <em>adapter</em>. It implements the kiosk's small
 * {@code PaymentTerminal} contract and translates it into the hardware stack's
 * vocabulary -- {@code Rc522Driver}, {@code Rc522Reader}, {@code IsoDepChannel},
 * {@code DesfireCard} -- and translates that stack's {@code SpiException} back
 * into the kiosk's {@code CardException} family. Neither side knows the other's
 * types, which is exactly why the whole UI could be built and tested against
 * {@link SimulatedPaymentTerminal} long before any of this existed: the UI
 * depends on the interface, and this is just one more thing that satisfies it.
 *
 * <p>The reader is opened once, at construction, because bringing up Pi4J and
 * the RC522 takes over a second and a kiosk should pay that at boot, not on
 * every tap. Each transaction runs on a single background thread so
 * {@link #startTransaction} can return immediately; the callbacks are delivered
 * from that thread, and {@link com.midnightbrewer.ui.UIController}'s listener
 * marshals them onto the JavaFX thread.
 */
public class DesfirePaymentTerminal implements PaymentTerminal {

    // How the card was personalised (matches HardwareCheck and app_config.dart).
    private static final byte[] APP_AID       = {0x03, 0x02, 0x01}; // AID 0x010203, LSB-first
    private static final byte   USER_KEY_NO   = 0x02;
    private static final byte   VALUE_FILE_NO = 0x01;

    // Registers used by the init sequence (same as HardwareCheck.initChip).
    private static final int TX_CONTROL_REG = 0x14;
    private static final int ANTENNA_BITS   = 0x03;

    private final Pi4jSpiLink link;
    private final Rc522Driver driver;
    private final Rc522Reader reader;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "desfire-terminal");
                t.setDaemon(true);
                return t;
            });

    /** Set by {@link #cancel()} so the polling loop can bail out between reads. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean scanning = false;

    /** Opens the reader. Throws if there is no RC522 -- callers fall back to the simulator. */
    public DesfirePaymentTerminal() throws SpiException {
        this.link = new Pi4jSpiLink();
        this.driver = new Rc522Driver(link);
        try {
            initReader();
            int version = driver.version();
            if (version != 0x91 && version != 0x92) {
                throw new SpiException("RC522 not responding (VersionReg=0x"
                        + Integer.toHexString(version) + ")");
            }
        } catch (SpiException e) {
            quietClose();
            throw e;
        }
        this.reader = new Rc522Reader(driver);
    }

    /** The MFRC522 register writes a Type A card needs (see HardwareCheck.initChip). */
    private void initReader() throws SpiException {
        driver.softReset();
        sleep(50);
        driver.writeRegister(0x2A, 0x8D); // TModeReg    } response timer,
        driver.writeRegister(0x2B, 0x3E); // TPrescaler  } ~300 ms
        driver.writeRegister(0x2C, 0x02); // TReloadH    }
        driver.writeRegister(0x2D, 0x58); // TReloadL    }  (0x0258 = 600)
        driver.writeRegister(0x15, 0x40); // TxAutoReg  -> force 100% ASK
        driver.writeRegister(0x11, 0x3D); // ModeReg    -> CRC preset 0x6363
        driver.writeRegister(0x26, 0x08); // RFCfgReg   -> RX gain
        driver.antennaOn();
    }

    private static byte[] userKey() {
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0x22);
        return key;
    }

    @Override
    public void startTransaction(Drink drink, PaymentListener listener) {
        cancelled.set(false);
        worker.submit(() -> runTransaction(drink, listener));
    }

    /**
     * The whole charge sequence, on the worker thread.
     *
     * <p>Poll for a card, activate it, authenticate, read the balance, and --
     * only if there are sufficient funds -- debit and commit. Exactly one
     * terminal callback ({@code onApproved} or {@code onDeclined}) is delivered
     * unless the transaction is cancelled first.
     */
    private void runTransaction(Drink drink, PaymentListener listener) {
        scanning = true;
        try {
            // Fresh reader state each tap: softReset + antennaOn power-cycles the
            // field, so the card is IDLE again and activate() runs cleanly even
            // after a previous purchase.
            initReader();

            if (!waitForCard()) {
                return; // cancelled while polling; the UI already moved on
            }

            ActivatedCard card = reader.activate();
            String uid = card.uidHex();
            listener.onCardDetected(uid);

            DesfireCard desfire =
                    new DesfireCard(new IsoDepChannel(reader), RandomSource.secure());
            desfire.selectApplication(APP_AID);
            desfire.authenticateEv2First(USER_KEY_NO, userKey());

            int balance = desfire.getValue(VALUE_FILE_NO);
            listener.onBalanceRead(balance);

            int price = drink.getPriceCents();
            if (balance < price) {
                listener.onDeclined(new InsufficientFundsException(balance, price));
                return;
            }

            // The debit is provisional until the commit is acknowledged; only
            // then has the money actually moved and only then may we dispense.
            desfire.debit(VALUE_FILE_NO, price);
            desfire.commitTransaction();
            int after = desfire.getValue(VALUE_FILE_NO);

            listener.onApproved(new PaymentReceipt(uid, drink, balance, after));
        } catch (SpiException e) {
            listener.onDeclined(translate(e));
        } catch (RuntimeException e) {
            listener.onDeclined(new CardCommunicationException(
                    "unexpected reader error: " + e.getMessage(), e));
        } finally {
            scanning = false;
        }
    }

    /** Polls until a card is in the field or the transaction is cancelled. */
    private boolean waitForCard() throws SpiException {
        while (!cancelled.get()) {
            if (reader.isCardPresent()) {
                return true;
            }
            sleep(30);
        }
        return false;
    }

    /**
     * Turns a hardware-stack {@link SpiException} into the kiosk's vocabulary,
     * so the UI never sees a {@code com.midnightbrewer.hardware.*} type.
     *
     * <p>Insufficient funds is caught earlier by the balance check, so here the
     * common case worth naming is an authentication failure; everything else is
     * a communication error (card pulled from the field, framing, bad MAC).
     */
    private CardException translate(SpiException e) {
        String msg = e.getMessage() == null ? "reader error" : e.getMessage();
        if (msg.contains("Authentication") || msg.contains("RndA")) {
            return new AuthenticationFailedException(msg, USER_KEY_NO);
        }
        return new CardCommunicationException(msg, e);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public String getStatusText() {
        return scanning ? "RC522 SCANNING" : "RC522 READY";
    }

    @Override
    public void close() {
        cancelled.set(true);
        worker.shutdownNow();
        quietClose();
    }

    /** Best effort on the way out: power the field down and release SPI. */
    private void quietClose() {
        try {
            driver.clearBitMask(TX_CONTROL_REG, ANTENNA_BITS); // antenna off
        } catch (SpiException | RuntimeException ignored) {
            // nothing to do if it fails while shutting down
        }
        try {
            link.close();
        } catch (SpiException | RuntimeException ignored) {
            // ignore
        }
    }
}
