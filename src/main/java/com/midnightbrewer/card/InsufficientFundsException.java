package com.midnightbrewer.card;

import com.midnightbrewer.model.Drink;

/**
 * The card's stored value is lower than the price of the selected drink.
 *
 * <p>This is a business rule, not a hardware fault, and the kiosk should
 * check it <em>before</em> sending a Debit command. The card would refuse a
 * debit that underflows anyway (with status {@code 0x1E BOUNDARY_ERROR}), but
 * checking first lets you show a useful message instead of a raw error code.
 */
public class InsufficientFundsException extends CardException {

    private static final long serialVersionUID = 1L;

    private final int balanceCents;
    private final int requiredCents;

    public InsufficientFundsException(int balanceCents, int requiredCents) {
        super("balance " + balanceCents + " < required " + requiredCents);
        this.balanceCents = balanceCents;
        this.requiredCents = requiredCents;
    }

    public int getBalanceCents() {
        return balanceCents;
    }

    public int getRequiredCents() {
        return requiredCents;
    }

    public int getShortfallCents() {
        return requiredCents - balanceCents;
    }

    @Override
    public String getUserMessage() {
        return "Not enough credit. You need "
                + Drink.formatCents(getShortfallCents()) + " more.";
    }
}
