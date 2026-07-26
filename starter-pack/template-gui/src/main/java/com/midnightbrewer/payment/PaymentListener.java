package com.midnightbrewer.payment;

/**
 * Callbacks fired as a transaction progresses.
 *
 * THREADING CONTRACT: a PaymentTerminal works on a background thread, so these
 * methods are invoked OFF the JavaFX application thread. Implementations that
 * touch the scene graph MUST wrap their work in Platform.runLater(...).
 *
 * Touching a JavaFX node from the wrong thread does not reliably throw -- it
 * can corrupt rendering in ways that show up once an hour and never under a
 * debugger. This is the nastiest bug class in UI programming.
 */
public interface PaymentListener {

    /** A card entered the field. Not yet authenticated. */
    void onCardDetected(String uid);

    /** Authenticated and balance read, before any debit. */
    void onBalanceRead(int balanceCents);

    /** Debit COMMITTED. The drink may now be dispensed. */
    void onApproved(PaymentReceipt receipt);

    /** Failed. The card was not charged, or the debit was rolled back. */
    void onDeclined(String reason);
}
