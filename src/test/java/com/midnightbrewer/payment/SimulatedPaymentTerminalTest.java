package com.midnightbrewer.payment;

import com.midnightbrewer.card.CardException;
import com.midnightbrewer.card.InsufficientFundsException;
import com.midnightbrewer.model.Drink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The money-safety tests for the simulator.
 *
 * <p>These exist because of an actual bug: the late-callback guard was
 * {@code pending != null || !transactionActive}, and since {@code cancel()}
 * clears both, that OR let cancelled callbacks through — the kiosk walked on
 * to the brewing screen and dispensed a free drink after the user pressed
 * Cancel. {@code shouldNotDispenseAfterCancel} is the regression test.
 *
 * <p>When you write {@code DesfirePaymentTerminal}, make it pass this same
 * set. The rules are identical whether the wallet is in memory or on a card.
 */
class SimulatedPaymentTerminalTest {

    private static final Drink MOCHA = new Drink("mocha", "Mocha", 350);
    private static final int START_BALANCE = 2500;

    /** Collects callbacks and lets a test block until one arrives. */
    private static final class RecordingListener implements PaymentListener {
        final AtomicBoolean approved = new AtomicBoolean(false);
        final AtomicReference<CardException> declined = new AtomicReference<>();
        final AtomicReference<PaymentReceipt> receipt = new AtomicReference<>();
        final CountDownLatch terminal = new CountDownLatch(1);

        @Override public void onCardDetected(String uid) { }
        @Override public void onBalanceRead(int balanceCents) { }

        @Override
        public void onApproved(PaymentReceipt r) {
            receipt.set(r);
            approved.set(true);
            terminal.countDown();
        }

        @Override
        public void onDeclined(CardException cause) {
            declined.set(cause);
            terminal.countDown();
        }

        boolean awaitOutcome(long ms) throws InterruptedException {
            return terminal.await(ms, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    @DisplayName("a normal tap debits the card and returns a receipt")
    void shouldDebitOnSuccessfulTap() throws Exception {
        try (SimulatedPaymentTerminal terminal = new SimulatedPaymentTerminal()) {
            RecordingListener listener = new RecordingListener();
            terminal.startTransaction(MOCHA, listener);
            terminal.simulateTap();

            assertTrue(listener.awaitOutcome(3000), "no outcome delivered");
            assertTrue(listener.approved.get(), "should have been approved");

            PaymentReceipt r = listener.receipt.get();
            assertEquals(START_BALANCE, r.getBalanceBeforeCents());
            assertEquals(START_BALANCE - 350, r.getBalanceAfterCents());
            assertEquals(350, r.getAmountChargedCents());
            assertEquals(START_BALANCE - 350, terminal.getBalanceCents());
        }
    }

    @Test
    @DisplayName("cancelling mid-transaction must not dispense or charge")
    void shouldNotDispenseAfterCancel() throws Exception {
        try (SimulatedPaymentTerminal terminal = new SimulatedPaymentTerminal()) {
            RecordingListener listener = new RecordingListener();
            terminal.startTransaction(MOCHA, listener);
            terminal.simulateTap();

            // Cancel after detection but before the debit step lands.
            Thread.sleep(200);
            terminal.cancel();

            // Every scheduled callback must have expired harmlessly by now.
            assertFalse(listener.awaitOutcome(1500),
                    "a cancelled transaction still delivered an outcome");
            assertFalse(listener.approved.get(), "dispensed after cancel");
            assertEquals(START_BALANCE, terminal.getBalanceCents(),
                    "card was charged despite cancellation");
        }
    }

    @Test
    @DisplayName("an underfunded card is declined and never charged")
    void shouldDeclineWhenBalanceTooLow() throws Exception {
        try (SimulatedPaymentTerminal terminal = new SimulatedPaymentTerminal()) {
            terminal.setBalanceCents(120);

            RecordingListener listener = new RecordingListener();
            terminal.startTransaction(MOCHA, listener);
            terminal.simulateTap();

            assertTrue(listener.awaitOutcome(3000), "no outcome delivered");
            assertFalse(listener.approved.get(), "approved despite $1.20 balance");

            CardException cause = listener.declined.get();
            InsufficientFundsException funds =
                    assertInstanceOf(InsufficientFundsException.class, cause);
            assertEquals(230, funds.getShortfallCents());
            assertEquals(120, terminal.getBalanceCents(), "charged on a decline");
        }
    }

    @Test
    @DisplayName("losing the card mid-transaction declines without charging")
    void shouldDeclineWhenCardLeavesField() throws Exception {
        try (SimulatedPaymentTerminal terminal = new SimulatedPaymentTerminal()) {
            RecordingListener listener = new RecordingListener();
            terminal.startTransaction(MOCHA, listener);
            terminal.simulateCardRemoved();

            assertTrue(listener.awaitOutcome(2000), "no outcome delivered");
            assertFalse(listener.approved.get(), "approved after card removal");
            assertEquals(START_BALANCE, terminal.getBalanceCents());
        }
    }

    @Test
    @DisplayName("balance drains across repeated purchases until declined")
    void shouldDrainBalanceAcrossPurchases() throws Exception {
        try (SimulatedPaymentTerminal terminal = new SimulatedPaymentTerminal()) {
            terminal.setBalanceCents(800);

            for (int i = 0; i < 2; i++) {
                RecordingListener listener = new RecordingListener();
                terminal.startTransaction(MOCHA, listener);
                terminal.simulateTap();
                assertTrue(listener.awaitOutcome(3000), "purchase " + i + " stalled");
                assertTrue(listener.approved.get(), "purchase " + i + " declined");
            }
            assertEquals(100, terminal.getBalanceCents());

            // $1.00 left, mocha is $3.50 — must decline.
            RecordingListener third = new RecordingListener();
            terminal.startTransaction(MOCHA, third);
            terminal.simulateTap();
            assertTrue(third.awaitOutcome(3000), "third purchase stalled");
            assertFalse(third.approved.get(), "approved with only $1.00 left");
            assertEquals(100, terminal.getBalanceCents());
        }
    }

    @Test
    @DisplayName("money is formatted from integer cents, never floating point")
    void shouldFormatCents() {
        assertEquals("$3.50", Drink.formatCents(350));
        assertEquals("$0.05", Drink.formatCents(5));
        assertEquals("$25.00", Drink.formatCents(2500));
        assertEquals("$0.00", Drink.formatCents(0));
        // 0.1 + 0.2 != 0.3 in binary floating point; cents have no such problem.
        assertEquals("$0.30", Drink.formatCents(10 + 20));
    }
}
