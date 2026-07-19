package com.midnightbrewer.payment;

import com.midnightbrewer.card.CardCommunicationException;
import com.midnightbrewer.card.InsufficientFundsException;
import com.midnightbrewer.model.Drink;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory stand-in for a DESFire card, so the kiosk is fully usable
 * before any hardware is wired up.
 *
 * <p>It deliberately imitates the parts of the real flow that shape the UI:
 * a detection delay, a separate "balance read" step, and a real insufficient
 * funds path. That way the screens you build against this simulator do not
 * need rework once {@code DesfirePaymentTerminal} replaces it.
 *
 * <p>The virtual card's balance persists for the lifetime of the process, so
 * you can buy several drinks and watch it drain to a decline.
 */
public class SimulatedPaymentTerminal implements PaymentTerminal {

    /** Matches the DESFire value file: signed integer cents. */
    private final AtomicReference<Integer> balanceCents = new AtomicReference<>(2500);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "simulated-terminal");
                t.setDaemon(true); // never block JVM shutdown
                return t;
            });

    private final AtomicBoolean transactionActive = new AtomicBoolean(false);
    private final AtomicReference<PendingTransaction> pending = new AtomicReference<>();

    private static final class PendingTransaction {
        final Drink drink;
        final PaymentListener listener;

        PendingTransaction(Drink drink, PaymentListener listener) {
            this.drink = drink;
            this.listener = listener;
        }
    }

    @Override
    public void startTransaction(Drink drink, PaymentListener listener) {
        if (!transactionActive.compareAndSet(false, true)) {
            throw new IllegalStateException("a transaction is already in flight");
        }
        pending.set(new PendingTransaction(drink, listener));
        System.out.printf("[SIM] awaiting card for %s%n", drink);
    }

    /**
     * Stands in for the physical act of tapping a card. Wired to the hidden
     * dev button on the payment screen.
     */
    public void simulateTap() {
        PendingTransaction tx = pending.get();
        if (tx == null) {
            System.out.println("[SIM] tap ignored — no transaction in flight");
            return;
        }

        // Staggered like the real thing: detect, then authenticate + read, then debit.
        schedule(120, () -> tx.listener.onCardDetected("04A2B3C4D5E680"));
        schedule(420, () -> {
            int balance = balanceCents.get();
            tx.listener.onBalanceRead(balance);

            int price = tx.drink.getPriceCents();
            if (balance < price) {
                finish(tx, () -> tx.listener.onDeclined(
                        new InsufficientFundsException(balance, price)));
                return;
            }

            schedule(380, () -> {
                int after = balance - price;
                balanceCents.set(after);
                finish(tx, () -> tx.listener.onApproved(
                        new PaymentReceipt("04A2B3C4D5E680", tx.drink, balance, after)));
            });
        });
    }

    /** Stands in for yanking the card away mid-transaction. */
    public void simulateCardRemoved() {
        PendingTransaction tx = pending.get();
        if (tx != null) {
            finish(tx, () -> tx.listener.onDeclined(
                    new CardCommunicationException("card left the field")));
        }
    }

    private void finish(PendingTransaction tx, Runnable emit) {
        if (pending.compareAndSet(tx, null)) {
            transactionActive.set(false);
            emit.run();
        }
    }

    private void schedule(long delayMs, Runnable action) {
        scheduler.schedule(() -> {
            // Drop late callbacks from a transaction that was already
            // cancelled or finished. `pending` is the single source of truth:
            // cancel() and finish() both null it, and a live transaction
            // always has it set. Do NOT also test transactionActive here —
            // cancel() clears both, so an OR over the two lets cancelled
            // callbacks through and dispenses a free drink.
            if (pending.get() != null) {
                action.run();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel() {
        pending.set(null);
        transactionActive.set(false);
        System.out.println("[SIM] transaction cancelled");
    }

    /** Exposed so the top-up demo and tests can reset the virtual wallet. */
    public void setBalanceCents(int cents) {
        balanceCents.set(cents);
    }

    public int getBalanceCents() {
        return balanceCents.get();
    }

    @Override
    public String getStatusText() {
        return "SIMULATOR • " + Drink.formatCents(balanceCents.get());
    }

    @Override
    public void close() {
        cancel();
        scheduler.shutdownNow();
    }
}
