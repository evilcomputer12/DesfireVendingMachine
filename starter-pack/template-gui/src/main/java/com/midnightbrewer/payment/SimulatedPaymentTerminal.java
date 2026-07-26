package com.midnightbrewer.payment;

import com.midnightbrewer.model.Drink;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A fake payment terminal with an in-memory wallet. No hardware.
 *
 * <p>Given to you complete, for one reason: it is what lets you build the
 * entire UI before any card exists. Read it anyway --- the threading here is
 * exactly the shape the real DESFire terminal takes in Volume One,
 * Chapter&nbsp;12.
 *
 * <p>It waits for {@link #simulateTap()} instead of a card, then walks the same
 * callback sequence a real terminal does:
 * {@code onCardDetected -> onBalanceRead -> onApproved | onDeclined}.
 */
public class SimulatedPaymentTerminal implements PaymentTerminal {

    private static final String FAKE_UID = "04 76 18 62 7B 20 90";

    /** The pretend card's balance, in cents. Starts at 5.00. */
    private int balanceCents = 500;

    /**
     * ONE background thread, created once. Transactions are strictly
     * sequential: two overlapping card conversations would interleave and
     * corrupt each other, so the design forbids it structurally.
     *
     * A daemon thread never blocks JVM shutdown -- without this, closing the
     * window would leave the process alive.
     */
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "simulated-terminal");
                t.setDaemon(true);
                return t;
            });

    /** Written by the UI thread, read by the worker -- hence Atomic/volatile. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean tapped = new AtomicBoolean(false);
    private volatile boolean scanning = false;

    @Override
    public void startTransaction(Drink drink, PaymentListener listener) {
        cancelled.set(false);
        tapped.set(false);
        worker.submit(() -> runTransaction(drink, listener));   // returns instantly
    }

    /** Called by the dev "simulate tap" button. */
    public void simulateTap() {
        tapped.set(true);
    }

    private void runTransaction(Drink drink, PaymentListener listener) {
        scanning = true;
        try {
            // Wait for a "card", or for the user to cancel.
            while (!tapped.get()) {
                if (cancelled.get()) {
                    return;                       // UI already moved on
                }
                sleep(30);
            }

            listener.onCardDetected(FAKE_UID);
            sleep(400);                           // pretend authentication

            listener.onBalanceRead(balanceCents);
            sleep(300);

            int price = drink.getPriceCents();
            if (balanceCents < price) {
                listener.onDeclined("Insufficient funds: balance "
                        + Drink.formatCents(balanceCents)
                        + ", need " + Drink.formatCents(price));
                return;
            }

            int before = balanceCents;
            balanceCents -= price;                // the "debit"
            listener.onApproved(
                    new PaymentReceipt(FAKE_UID, drink, before, balanceCents));

        } catch (RuntimeException e) {
            // A kiosk must never die on a bad transaction.
            listener.onDeclined("unexpected error: " + e.getMessage());
        } finally {
            scanning = false;
        }
    }

    /** Top the fake card back up, so you can keep testing. */
    public void topUp(int cents) {
        balanceCents += cents;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public String getStatusText() {
        return scanning ? "SIMULATOR SCANNING" : "SIMULATOR READY";
    }

    @Override
    public void close() {
        cancelled.set(true);
        worker.shutdownNow();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
