# 11 — The kiosk UI

**Goal:** a touchscreen that takes payments — without a single line of crypto in the UI.

**OOP concept:** the Adapter pattern, and threading discipline.

---

## The problem

You have a working payment stack. Now you need a screen: pick a drink, tap a card, watch it brew. Two constraints make this harder than it looks:

1. **You want to build the UI before the hardware works** — or at minimum, be able to develop it on a laptop with no reader attached.
2. **Card operations take seconds** (polling, RF, crypto) and a UI that blocks for seconds is a broken UI.

## The idea: one interface, two implementations

Define what the UI needs in the smallest possible terms, then satisfy it twice: once with a simulator, once with real hardware.

```
        JavaFX UI  ──depends only on──▶  PaymentTerminal (interface)
                                                ▲
                              ┌─────────────────┴─────────────────┐
                    SimulatedPaymentTerminal            DesfirePaymentTerminal
                    (laptop, no hardware)               (your whole card stack)
```

This is the same seam idea as `SpiLink` and `ApduChannel`, now at the top of the stack. It means **the entire UI can be built and tested before a single card exists**, and it means the kiosk degrades gracefully: no reader attached → simulator, and the app still runs.

---

## The contract

```java
package com.midnightbrewer.payment;

import com.midnightbrewer.model.Drink;

/**
 * The seam between the kiosk UI and whatever actually takes the money.
 *
 * The UI depends only on this type, so it neither knows nor cares whether
 * payment is a simulated wallet or a real DESFire being debited over SPI.
 *
 * Extends AutoCloseable so the RF field is always powered down, even on a
 * crash.
 */
public interface PaymentTerminal extends AutoCloseable {

    /**
     * Begin waiting for a card and charge it for drink.
     *
     * MUST return immediately -- all polling happens on a background thread.
     * Exactly one terminal callback (approved or declined) is delivered per
     * call, unless cancel() intervenes.
     */
    void startTransaction(Drink drink, PaymentListener listener);

    /** Abort the in-flight transaction. Safe to call when nothing is running. */
    void cancel();

    /** Human-readable state for the status pill, e.g. "RC522 READY". */
    String getStatusText();

    @Override
    void close();
}
```

```java
public interface PaymentListener {
    /** A card entered the field. Not yet authenticated. */
    void onCardDetected(String uid);
    /** Authenticated and balance read, before any debit. */
    void onBalanceRead(int balanceCents);
    /** Debit COMMITTED. The drink may now be dispensed. */
    void onApproved(PaymentReceipt receipt);
    /** Failed. The card was not charged, or the debit was rolled back. */
    void onDeclined(CardException cause);
}
```

> **Why callbacks instead of a return value?** Because `startTransaction` returns instantly and the result arrives seconds later. A blocking `boolean pay(Drink)` would freeze the UI thread — the animation would stop, touches would queue up, and the OS would offer to kill your app.
>
> **Why four callbacks and not one?** So the UI can narrate. "Card detected…" → "Balance €5.00, charging…" → approved. A payment terminal that shows nothing for four seconds feels broken even when it's working.

---

## The Adapter

Now the interesting class: something that satisfies `PaymentTerminal` by driving the stack you built in Chapters 1–10.

> **Analogy.** A travel plug adapter. Your laptop charger has one plug shape; the Italian wall has another. The adapter doesn't generate electricity or change the laptop — it sits between two fixed interfaces and translates. Both sides were designed without knowledge of each other, and the adapter makes them fit.
>
> Here the kiosk speaks `PaymentTerminal`/`CardException`; your stack speaks `Rc522Reader`/`DesfireCard`/`SpiException`. `DesfirePaymentTerminal` translates — including the exceptions, so a `com.midnightbrewer.hardware` type never leaks into the UI.

```java
package com.midnightbrewer.payment;

public class DesfirePaymentTerminal implements PaymentTerminal {

    // How the card was personalised (Chapter 12 / Appendix A).
    private static final byte[] APP_AID       = { 0x03, 0x02, 0x01 };  // 0x010203, LSB-first
    private static final byte   USER_KEY_NO   = 0x02;
    private static final byte   VALUE_FILE_NO = 0x01;

    private final Pi4jSpiLink link;
    private final Rc522Driver driver;
    private final Rc522Reader reader;

    // ONE background thread, created once. Transactions are strictly
    // sequential -- two overlapping card conversations would interleave
    // command counters and corrupt both.
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "desfire-terminal");
                t.setDaemon(true);      // never blocks JVM shutdown
                return t;
            });

    /** volatile/atomic: written by the UI thread, read by the worker. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean scanning = false;

    /** Opens the reader. THROWS if there is no RC522 -- see createTerminal(). */
    public DesfirePaymentTerminal() throws SpiException {
        this.link = new Pi4jSpiLink();
        this.driver = new Rc522Driver(link);
        try {
            initReader();
            int version = driver.version();
            if (version != 0x91 && version != 0x92) {
                throw new SpiException("RC522 not responding");
            }
        } catch (SpiException e) {
            quietClose();               // don't leak SPI on a failed construction
            throw e;
        }
        this.reader = new Rc522Reader(driver);
    }

    @Override
    public void startTransaction(Drink drink, PaymentListener listener) {
        cancelled.set(false);
        worker.submit(() -> runTransaction(drink, listener));   // returns instantly
    }

    private void runTransaction(Drink drink, PaymentListener listener) {
        scanning = true;
        try {
            // Fresh reader state each tap: softReset + antennaOn power-cycles
            // the field so the card is IDLE again and activate() runs cleanly
            // even after a previous purchase.
            initReader();

            if (!waitForCard()) return;             // cancelled; UI already moved on

            ActivatedCard card = reader.activate();
            listener.onCardDetected(card.uidHex());

            DesfireCard desfire =
                    new DesfireCard(new IsoDepChannel(reader), RandomSource.secure());
            desfire.selectApplication(APP_AID);
            desfire.authenticateEv2First(USER_KEY_NO, userKey());

            int balance = desfire.getValue(VALUE_FILE_NO);
            listener.onBalanceRead(balance);

            int price = drink.getPriceCents();
            if (balance < price) {
                listener.onDeclined(new InsufficientFundsException(balance, price));
                return;
            }

            // Provisional until the commit is acknowledged; only THEN may we
            // hand out a receipt, and only then may the UI dispense.
            desfire.debit(VALUE_FILE_NO, price);
            desfire.commitTransaction();
            int after = desfire.getValue(VALUE_FILE_NO);

            listener.onApproved(new PaymentReceipt(card.uidHex(), drink, balance, after));

        } catch (SpiException e) {
            listener.onDeclined(translate(e));      // stack vocabulary -> UI vocabulary
        } catch (RuntimeException e) {
            listener.onDeclined(new CardCommunicationException(
                    "unexpected reader error: " + e.getMessage(), e));
        } finally {
            scanning = false;
        }
    }

    private boolean waitForCard() throws SpiException {
        while (!cancelled.get()) {
            if (reader.isCardPresent()) return true;
            sleep(30);
        }
        return false;
    }
    // translate(), initReader(), close()... see the repo
}
```

### The `catch (RuntimeException)` is not paranoia

A kiosk must never die on a bad tap. `SpiException` covers expected failures; the `RuntimeException` catch covers the unexpected — an array index, a null, a Pi4J internal error. Without it, one odd card kills the worker thread and the machine stops taking payments until someone reboots it. **At a process boundary, catch broadly and report; inside your logic, catch narrowly.**

---

## Threading: the rule you must not break

JavaFX has exactly one thread allowed to touch the UI. Your terminal calls back from a *different* thread. So every callback hops:

```java
private final class UiPaymentListener implements PaymentListener {

    @Override
    public void onCardDetected(String uid) {
        Platform.runLater(() -> paymentHintLabel.setText("Card detected — authenticating…"));
    }

    @Override
    public void onApproved(PaymentReceipt receipt) {
        Platform.runLater(() -> { stopPulse(); startBrewing(receipt); });
    }
    // ...
}
```

> ⚠️ **Touching a JavaFX node from the wrong thread does not reliably throw.** Sometimes it works. Sometimes it corrupts rendering in ways that appear once an hour, on the real panel only, and never under a debugger. This is one of the nastiest bug classes in UI programming, and the fix is a habit, not a diagnosis: **every callback that touches the scene graph is wrapped in `Platform.runLater`.** No exceptions.

The same rule exists everywhere: Android has `runOnUiThread`, Swing has `invokeLater`, Qt has queued connections. One thread owns the widgets.

---

## Choosing the backend

The whole design collapses into one method:

```java
private PaymentTerminal createTerminal() {
    if ("simulated".equalsIgnoreCase(System.getProperty("brewer.reader", ""))) {
        return new SimulatedPaymentTerminal();          // forced, for UI work
    }
    try {
        PaymentTerminal real = new DesfirePaymentTerminal();
        System.out.println("[UI] RC522 ready -- real card payment enabled");
        return real;
    } catch (Exception e) {
        System.out.println("[UI] no RC522 (" + e.getMessage() + ") -- using simulator");
        return new SimulatedPaymentTerminal();
    }
}
```

**Detection by attempting to open.** On a laptop, constructing `Pi4jSpiLink` throws, and you fall back. Same build runs everywhere, no flags, no config file, no `#ifdef`. This is why the constructor *must* throw rather than returning a half-built object.

And because everything above depends on the interface, the UI needs **zero** changes to switch backends. The bench "simulate tap" button even hides itself:

```java
devTapButton.setVisible(terminal instanceof SimulatedPaymentTerminal);
```

---

## Verify

**On a laptop** (no hardware): `mvn javafx:run -Dbrewer.windowed=true`. The simulator's tap button drives the whole flow.

**On the Pi**, headless smoke test first — it runs the exact path a tap takes, minus the screen:

```java
public final class TerminalCheck {
    public static void main(String[] args) throws Exception {
        Drink testDrink = new Drink("test", "Test Shot", 10);   // 10 cents
        try (DesfirePaymentTerminal terminal = new DesfirePaymentTerminal()) {
            CountDownLatch done = new CountDownLatch(1);
            terminal.startTransaction(testDrink, new PaymentListener() {
                public void onCardDetected(String uid)   { System.out.println("  card: " + uid); }
                public void onBalanceRead(int cents)     { System.out.println("  balance: " + cents); }
                public void onApproved(PaymentReceipt r) { System.out.println("  APPROVED"); done.countDown(); }
                public void onDeclined(CardException e)  { System.out.println("  DECLINED: " + e.getUserMessage()); done.countDown(); }
            });
            System.out.println("Tap a card...");
            done.await();
        }
    }
}
```

`CountDownLatch` is how `main` waits for a background thread: start at 1, block on `await()`, the callback counts down to 0 and releases it.

**Success:**

```
  card: 04 76 18 62 7B 20 90
  balance: $5.00
  APPROVED: charged $0.10  ($5.00 -> $4.90)
```

---

## Traps

| Symptom | Cause |
|---|---|
| UI freezes on tap | Card work on the FX thread. It must be on the worker. |
| Random rendering corruption | A callback touching nodes without `Platform.runLater`. |
| Second tap always fails | Reader not re-initialised per transaction (field never power-cycled). |
| Kiosk dies on an odd card | Missing the broad `RuntimeException` catch. |
| Simulator used on the Pi | Constructor threw — read the printed message, it names the cause. |
| Drink dispensed but card not charged | Dispensing on `debit` instead of after `commitTransaction`. |

---

## Commit

```bash
git add -A
git commit -m "kiosk: PaymentTerminal seam + DesfirePaymentTerminal adapter"
```

---

*Next: [12 — The Android top-up app](12-android-topup.md)*
