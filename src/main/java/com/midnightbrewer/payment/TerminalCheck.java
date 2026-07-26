package com.midnightbrewer.payment;

import com.midnightbrewer.card.CardException;
import com.midnightbrewer.model.Drink;

import java.util.concurrent.CountDownLatch;

/**
 * Headless smoke test for {@link DesfirePaymentTerminal}: runs one real
 * transaction against a card on the antenna, exactly like the kiosk does on a
 * tap -- but without the JavaFX UI. Proves the adapter (threading, per-tap
 * init, activate, auth, getValue, debit, commit, receipt) works on silicon.
 *
 * Debits a 10-cent "Test Shot" so it barely touches the balance.
 */
public final class TerminalCheck {

    private TerminalCheck() {
    }

    public static void main(String[] args) throws Exception {
        Drink testDrink = new Drink("test", "Test Shot", 10); // 10 cents

        System.out.println("Opening DesfirePaymentTerminal...");
        try (DesfirePaymentTerminal terminal = new DesfirePaymentTerminal()) {
            System.out.println("Terminal up: " + terminal.getStatusText());

            CountDownLatch done = new CountDownLatch(1);
            terminal.startTransaction(testDrink, new PaymentListener() {
                @Override public void onCardDetected(String uid) {
                    System.out.println("  card detected: " + uid);
                }
                @Override public void onBalanceRead(int cents) {
                    System.out.println("  balance: " + Drink.formatCents(cents));
                }
                @Override public void onApproved(PaymentReceipt r) {
                    System.out.println("  APPROVED: charged "
                            + Drink.formatCents(r.getAmountChargedCents()) + "  ("
                            + Drink.formatCents(r.getBalanceBeforeCents()) + " -> "
                            + Drink.formatCents(r.getBalanceAfterCents()) + ")");
                    done.countDown();
                }
                @Override public void onDeclined(CardException e) {
                    System.out.println("  DECLINED: " + e.getUserMessage());
                    done.countDown();
                }
            });

            System.out.println("Tap a card on the antenna...");
            done.await();
        }
        System.out.println("Terminal closed.");
    }
}
