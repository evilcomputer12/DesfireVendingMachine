package com.midnightbrewer.payment;

import com.midnightbrewer.model.Drink;

/**
 * The seam between the kiosk UI and whatever actually takes the money.
 *
 * The single most important interface in the project. The UI depends only on
 * this type, so it neither knows nor cares whether payment is a simulated
 * wallet or a real DESFire card being debited over SPI.
 *
 * Two implementations are expected:
 *   - SimulatedPaymentTerminal -- works today, no hardware.
 *   - DesfirePaymentTerminal   -- you write this in Volume One, Chapter 12.
 */
public interface PaymentTerminal extends AutoCloseable {

    /**
     * Begin waiting for a card and charge it for {@code drink}.
     *
     * MUST return immediately; all waiting happens on a background thread.
     * Exactly one terminal callback (approved or declined) is delivered per
     * call, unless {@link #cancel()} intervenes first.
     */
    void startTransaction(Drink drink, PaymentListener listener);

    /** Abort the in-flight transaction. Safe to call when nothing is running. */
    void cancel();

    /** Human-readable state for the status pill, e.g. "SIMULATOR READY". */
    String getStatusText();

    @Override
    void close();
}
