package com.midnightbrewer.payment;

import com.midnightbrewer.card.CardException;

/**
 * Callbacks fired as a transaction progresses.
 *
 * <p><strong>Threading contract:</strong> a {@link PaymentTerminal} polls on
 * a background thread, so these methods are invoked <em>off</em> the JavaFX
 * application thread. Implementations that touch the scene graph must wrap
 * their work in {@code Platform.runLater(...)}. Touching a JavaFX node from
 * the wrong thread does not always throw — it can corrupt rendering in ways
 * that only show up intermittently, which is miserable to debug.
 */
public interface PaymentListener {

    /** A card entered the field and was selected. Not yet authenticated. */
    void onCardDetected(String uid);

    /** Authentication succeeded and the balance was read, before any debit. */
    void onBalanceRead(int balanceCents);

    /** Debit committed. The drink may now be dispensed. */
    void onApproved(PaymentReceipt receipt);

    /** Transaction failed. The card was not charged, or the debit was aborted. */
    void onDeclined(CardException cause);
}
