package com.midnightbrewer.payment;

import com.midnightbrewer.model.Drink;

/**
 * Proof of a completed, committed debit.
 *
 * <p>Only construct one of these <em>after</em> CommitTransaction has been
 * acknowledged by the card. Before the commit, a Debit is provisional and the
 * card will silently roll it back — handing out a receipt at that point means
 * dispensing a drink you were never paid for.
 */
public final class PaymentReceipt {

    private final String cardUid;
    private final Drink drink;
    private final int balanceBeforeCents;
    private final int balanceAfterCents;

    public PaymentReceipt(String cardUid, Drink drink,
                          int balanceBeforeCents, int balanceAfterCents) {
        this.cardUid = cardUid;
        this.drink = drink;
        this.balanceBeforeCents = balanceBeforeCents;
        this.balanceAfterCents = balanceAfterCents;
    }

    public String getCardUid() {
        return cardUid;
    }

    public Drink getDrink() {
        return drink;
    }

    public int getBalanceBeforeCents() {
        return balanceBeforeCents;
    }

    public int getBalanceAfterCents() {
        return balanceAfterCents;
    }

    public int getAmountChargedCents() {
        return balanceBeforeCents - balanceAfterCents;
    }

    @Override
    public String toString() {
        return String.format("Receipt[uid=%s, %s, %s -> %s]",
                cardUid, drink.getDisplayName(),
                Drink.formatCents(balanceBeforeCents),
                Drink.formatCents(balanceAfterCents));
    }
}
