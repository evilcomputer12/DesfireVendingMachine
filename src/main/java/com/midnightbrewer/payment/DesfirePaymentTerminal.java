package com.midnightbrewer.payment;

import com.midnightbrewer.card.Iso14443Transceiver;
import com.midnightbrewer.model.Drink;

/**
 * <h2>YOUR IMPLEMENTATION GOES HERE</h2>
 *
 * Real card payment over the RC522. This class is deliberately left as a
 * skeleton — the walkthrough in {@code docs/DESFIRE-PAYMENT-TUTORIAL.md}
 * explains the protocol, and the method stubs below give you the order of
 * operations. The crypto and the command encoding are yours to write; that is
 * the part worth learning.
 *
 * <p>The kiosk already runs end to end against {@link SimulatedPaymentTerminal}.
 * When this class works, change one line in
 * {@code UIController#createTerminal()} and the UI needs no other edit. If you
 * find yourself wanting to change something in the UI to make this fit, the
 * abstraction is wrong — come back and fix it here instead.
 *
 * <p><strong>The one rule that matters:</strong> never call
 * {@code listener.onApproved(...)} until CommitTransaction has returned
 * {@code 0x00}. A Debit that is not committed is discarded by the card. Get
 * this backwards and the machine gives away free coffee.
 */
public class DesfirePaymentTerminal implements PaymentTerminal {

    /** Application ID holding the wallet. Matches {@code desfiire.c}. */
    private static final int WALLET_AID = 0x010203;

    /** File number of the value file inside that application. */
    private static final byte WALLET_FILE_NO = 0x01;

    /**
     * Key number the kiosk authenticates with.
     *
     * <p>This key must be able to Debit but NOT Credit — see the access-rights
     * note in {@code DesfireCommands}. A kiosk in a public hallway is
     * physically reachable by anyone with a screwdriver; assume its key will
     * eventually be extracted and make sure that key cannot mint money.
     */
    private static final byte KIOSK_KEY_NO = 0x02;

    private final Iso14443Transceiver reader;
    private volatile boolean polling;

    public DesfirePaymentTerminal(Iso14443Transceiver reader) {
        this.reader = reader;
    }

    // ═════════════════════════════════════════════════════════════════
    // STEP 1 — poll for a card, then activate it
    //
    //   Reuse the logic from platform_card_present() and
    //   platform_activate_card() in desfiire.c: REQA/WUPA, anticollision
    //   (both cascade levels — DESFire has a 7-byte UID, so SAK bit 0x04
    //   will be set and you must run cascade level 2), SELECT, then RATS.
    //
    //   Design question to settle before you write it: this method runs on a
    //   background thread and the UI can cancel at any moment. Where do you
    //   check the cancel flag so that a cancel during a half-finished
    //   authentication does not leave the card in a broken session state?
    // ═════════════════════════════════════════════════════════════════

    @Override
    public void startTransaction(Drink drink, PaymentListener listener) {
        throw new UnsupportedOperationException(
                "Not implemented yet — see docs/DESFIRE-PAYMENT-TUTORIAL.md, step 1");
    }

    // ═════════════════════════════════════════════════════════════════
    // STEP 2 — SelectApplication (0x5A), then AuthenticateEV2First (0x71)
    //
    //   Your C library already does both: df_select_application() and
    //   df_authenticate_ev2_first(). Port them, and keep the session state
    //   (sessKeyEnc, sessKeyMac, TI, cmdCounter) in one object rather than
    //   as loose fields here — that object is your DFSession struct.
    //
    //   Question: df_authenticate_ev2_first zeroes cmdCounter on success.
    //   Why must the counter be part of every subsequent CMAC, and what
    //   attack becomes possible if you leave it out?
    // ═════════════════════════════════════════════════════════════════

    // ═════════════════════════════════════════════════════════════════
    // STEP 3 — GetValue (0x6C), then decide
    //
    //   Read the balance and compare against drink.getPriceCents().
    //   If it is short, throw InsufficientFundsException BEFORE sending any
    //   Debit. The card would refuse anyway with 0xBE BOUNDARY_ERROR, but
    //   checking first lets you tell the user how much they are short.
    // ═════════════════════════════════════════════════════════════════

    // ═════════════════════════════════════════════════════════════════
    // STEP 4 — Debit (0xDC), then CommitTransaction (0xC7)
    //
    //   Two commands, and the gap between them is the dangerous part.
    //   Work through what happens if the card leaves the field:
    //
    //     - before Debit           -> nothing happened
    //     - between Debit and Commit -> card discards it on next power-up
    //     - after Commit, before you read the response -> AMBIGUOUS
    //
    //   That last case is the real problem and it has no clean local answer.
    //   The tutorial's "torn transaction" section covers the options.
    //   Decide which one you want before writing the code, not after.
    // ═════════════════════════════════════════════════════════════════

    @Override
    public void cancel() {
        polling = false;
        // Consider: should this also send AbortTransaction (0xA7)? What if
        // the card has already left the field?
        throw new UnsupportedOperationException(
                "Not implemented yet — see docs/DESFIRE-PAYMENT-TUTORIAL.md, step 5");
    }

    @Override
    public String getStatusText() {
        return polling ? "RC522 SCANNING" : "RC522 READY";
    }

    @Override
    public void close() {
        polling = false;
        reader.close();
    }
}
