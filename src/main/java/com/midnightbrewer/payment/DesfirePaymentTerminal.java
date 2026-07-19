package com.midnightbrewer.payment;

import com.midnightbrewer.card.AuthenticationFailedException;
import com.midnightbrewer.card.CardCommunicationException;
import com.midnightbrewer.card.CardException;
import com.midnightbrewer.card.CardStatusException;
import com.midnightbrewer.card.InsufficientFundsException;
import com.midnightbrewer.model.Drink;

import com.midnightbrewer.reference.desfire.DesfireApduChannel;
import com.midnightbrewer.reference.desfire.DesfireCard;
import com.midnightbrewer.reference.desfire.DesfireStatus;
import com.midnightbrewer.reference.desfire.RandomSource;
import com.midnightbrewer.reference.desfire.ValueFileSettings;
import com.midnightbrewer.reference.desfire.WalletProfile;
import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.DesfireStatusException;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.iso14443.ActivatedCard;
import com.midnightbrewer.reference.iso14443.Rc522IsoDepTransceiver;
import com.midnightbrewer.reference.pcd.Rc522Driver;
import com.midnightbrewer.reference.pcd.Register;
import com.midnightbrewer.reference.spi.Pi4jSpiLink;
import com.midnightbrewer.reference.util.Timebase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The real {@link PaymentTerminal}: charges a DESFire card through the
 * reference RC522 stack.
 *
 * <p>This is an <em>adapter</em>. It implements the kiosk's small
 * {@code PaymentTerminal} contract and translates it into the reference
 * library's very different vocabulary -- {@code Rc522Driver},
 * {@code Iso14443Transceiver}, {@code DesfireCard} -- and translates the
 * reference's exceptions back into the kiosk's. Neither side knows the other's
 * types. That is the whole reason the UI could be written and tested against a
 * simulator long before this class existed: the UI depends on the interface,
 * and this is just one more thing that satisfies it.
 *
 * <p>The reader is opened once, at construction, because bringing up Pi4J and
 * the RC522 takes over a second and a kiosk should pay that at boot, not on
 * every tap. Each transaction runs on a single background thread so
 * {@link #startTransaction} can return immediately; the callbacks are
 * delivered from that thread, and {@link UIController}'s listener marshals them
 * onto the JavaFX thread.
 */
public class DesfirePaymentTerminal implements PaymentTerminal {

    private final Pi4jSpiLink link;
    private final Rc522Driver driver;
    private final Rc522IsoDepTransceiver transceiver;
    private final WalletProfile profile;

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
    public DesfirePaymentTerminal() throws NfcException {
        this.profile = WalletProfile.defaults();
        this.link = Pi4jSpiLink.openDefault();
        this.driver = new Rc522Driver(link, Timebase.system(), ProtocolTrace.none());
        driver.initialise();

        int version = driver.version();
        if (version == 0x00 || version == 0xFF) {
            link.close();
            throw new com.midnightbrewer.reference.error.TransportException(
                    "RC522 not responding on SPI (VersionReg=0x"
                            + Integer.toHexString(version) + ")");
        }
        this.transceiver = new Rc522IsoDepTransceiver(driver);
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
            if (!waitForCard()) {
                return; // cancelled while polling; the UI already moved on
            }

            ActivatedCard card = transceiver.activate();
            String uid = card.uid().toString();
            listener.onCardDetected(uid);

            DesfireCard desfire = new DesfireCard(
                    new DesfireApduChannel(transceiver), RandomSource.secure(),
                    ProtocolTrace.none());

            desfire.selectApplication(profile.aid());
            desfire.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());

            int balance = readOrCreateBalance(desfire);
            listener.onBalanceRead(balance);

            int price = drink.getPriceCents();
            if (balance < price) {
                listener.onDeclined(new InsufficientFundsException(balance, price));
                return;
            }

            // The debit is provisional until the commit is acknowledged; only
            // then has the money actually moved and only then may we dispense.
            desfire.debit(profile.fileNo(), price);
            desfire.commitTransaction();
            int after = desfire.getValue(profile.fileNo());

            listener.onApproved(new PaymentReceipt(uid, drink, balance, after));
        } catch (NfcException e) {
            listener.onDeclined(translate(e));
        } catch (RuntimeException e) {
            listener.onDeclined(new CardCommunicationException(
                    "unexpected reader error: " + e.getMessage(), e));
        } finally {
            scanning = false;
            quietDeselect();
        }
    }

    /** Polls until a card is in the field or the transaction is cancelled. */
    private boolean waitForCard() throws NfcException {
        while (!cancelled.get()) {
            if (transceiver.isCardPresent()) {
                return true;
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Reads the balance, creating the value file first if the application is
     * present but the file is not -- the state a firmware-provisioned card is
     * in. Mirrors the wallet demo and the Flutter top-up app.
     */
    private int readOrCreateBalance(DesfireCard desfire) throws NfcException {
        try {
            return desfire.getValue(profile.fileNo());
        } catch (DesfireStatusException e) {
            if (e.status() != DesfireStatus.FILE_NOT_FOUND.code()) {
                throw e;
            }
        }
        // Creating a file needs the master key; reading the value needs the
        // user key -- so re-authenticate across the two.
        desfire.selectApplication(profile.aid());
        desfire.authenticateEv2First(0, profile.appMasterKey());
        desfire.createValueFile(ValueFileSettings.builder(profile.fileNo())
                .accessRights(profile.accessRights())
                .lowerLimit(profile.lowerLimit())
                .upperLimit(profile.upperLimit())
                .initialValue(profile.initialBalance())
                .build());
        desfire.selectApplication(profile.aid());
        desfire.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        return desfire.getValue(profile.fileNo());
    }

    /**
     * Turns a reference exception into the kiosk's vocabulary, so the UI never
     * sees a {@code com.midnightbrewer.reference.*} type.
     */
    private CardException translate(NfcException e) {
        if (e instanceof DesfireStatusException) {
            int status = ((DesfireStatusException) e).status();
            if (status == DesfireStatus.AUTHENTICATION_ERROR.code()) {
                return new AuthenticationFailedException(e.getMessage(), profile.userKeyNo());
            }
            if (status == DesfireStatus.BOUNDARY_ERROR.code()) {
                // The card refused a debit that would underflow -- treat as funds.
                return new InsufficientFundsException(0, 0);
            }
            return new CardStatusException((byte) status, "DESFire command");
        }
        // Timeout, framing error, card pulled from the field, etc.
        return new CardCommunicationException(e.getMessage(), e);
    }

    private void quietDeselect() {
        try {
            transceiver.deselect();
        } catch (NfcException ignored) {
            // The card is often already gone by the time we get here; that is
            // not an error worth surfacing.
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
        quietDeselect();
        // Best effort: power the field down and release SPI. Nothing to do if
        // it fails on the way out.
        try {
            driver.antennaOff();
        } catch (NfcException | RuntimeException ignored) {
            // ignore
        }
        try {
            link.close();
        } catch (NfcException | RuntimeException ignored) {
            // ignore
        }
    }
}
