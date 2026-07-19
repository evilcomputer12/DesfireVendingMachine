package com.midnightbrewer.payment;

import com.midnightbrewer.model.Drink;

/**
 * The seam between the kiosk UI and whatever actually takes the money.
 *
 * <p>This is the single most important interface in the project. The UI
 * depends only on this type, so it neither knows nor cares whether payment is
 * a simulated in-memory wallet or a real DESFire card being debited over SPI.
 * Swap the implementation, change nothing in the UI.
 *
 * <p>Two implementations are expected:
 * <ul>
 *   <li>{@link SimulatedPaymentTerminal} — works today, no hardware.</li>
 *   <li>{@code DesfirePaymentTerminal} — you write this one; see
 *       {@code docs/DESFIRE-PAYMENT-TUTORIAL.md}.</li>
 * </ul>
 *
 * <p>Extends {@link AutoCloseable} so the reader's RF field is always powered
 * down via try-with-resources, even if the kiosk crashes on the way out.
 */
public interface PaymentTerminal extends AutoCloseable {

    /**
     * Begin waiting for a card and charge it for {@code drink}.
     *
     * <p>Must return immediately; all polling happens on a background thread.
     * Exactly one terminal callback (approved or declined) is delivered per
     * call, unless {@link #cancel()} intervenes first.
     */
    void startTransaction(Drink drink, PaymentListener listener);

    /**
     * Abort the in-flight transaction and stop polling.
     *
     * <p>Must be safe to call when nothing is running, and must be safe to
     * call from the UI thread while a poll is in progress.
     */
    void cancel();

    /** Human-readable reader state for the status pill, e.g. "RC522 READY". */
    String getStatusText();

    @Override
    void close();
}
