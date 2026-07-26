# Midnight Brewer — GUI skeleton

The kiosk touchscreen, split so you can build it live on camera.

**The design is done. The behaviour is yours.**

| Given complete | Yours to write |
|---|---|
| `MidnightBrewer.fxml` — 4-screen layout | `UIController.java` — 13 TODOs |
| `styles.css` — dark theme, tiles, pulse rings | |
| `images/*.png` — drink artwork | |
| `MainApp.java` — window boilerplate | |
| `model/`, `payment/` contracts | |
| `SimulatedPaymentTerminal` — a fake card | |

## Run it now

```bash
mvn test          # Tests run: 3, Failures: 0
make gui          # opens a window
```

It launches and shows the kiosk chrome with an **empty grid**. That's the
starting point, not a bug — `buildDrinkGrid()` is TODO 1.

## The 13 TODOs, in order

Each one is a visible unlock. That ordering is deliberate: you never spend more
than a few minutes before something changes on screen.

| # | In | Unlocks |
|---|---|---|
| 1–2 | `buildDrinkGrid`, `createDrinkTile` | **the menu appears** |
| 3 | `showScreen` | screens switch |
| 4 | `handleDrinkSelected` | tapping a drink goes to the payment screen |
| 5–8 | `UiPaymentListener` | **the threading rule** — live status text |
| 9 | `handleCancel` | the cancel button works |
| 10 | `startBrewing` | progress bar + captions |
| 11–12 | `showSuccess`, `showFailure` | the result screen, both outcomes |
| 13 | `buildPulseAnimation` | the three tap rings |

Each TODO carries the code as a comment — you type it, you don't invent it. The
*reasoning* is in the surrounding prose, and in Volume One Chapter 12.

## The one rule you must not break

Every callback in `UiPaymentListener` runs on the terminal's **background
thread**, and JavaFX has exactly one thread allowed to touch the UI. So every
one hops:

```java
Platform.runLater(() -> paymentHintLabel.setText("..."));
```

Touching a node from the wrong thread **does not reliably throw**. It corrupts
rendering in ways that show up once an hour, on the real panel only, never under
a debugger. Make it a habit, not a diagnosis.

## On the Pi

```bash
make pi-setup       # one time
make deploy-full    # ship classes + resources + jars
make kiosk          # fullscreen on the touchscreen
```

`run-kiosk.sh` passes `-Dprism.forceGPU=true`, which is **not optional** — the
Pi's driver reports OpenGL 2.1, JavaFX rejects it and silently falls back to
software rendering, and everything crawls. `-Dprism.verbose=true` prints the
pipeline: you want `es2`, not `sw`.

## Filming this

Suggested episode split:

1. **"The design renders but does nothing"** → TODOs 1–3. Menu appears, screens switch.
2. **"Making it take money"** → TODOs 4–9. The simulator, and the threading rule.
3. **"Polish"** → TODOs 10–13. Brewing animation, result screen, pulse rings.
4. **Volume One Ch. 12** → replace the simulator with the real DESFire terminal. *One method changes.*

That last beat is the payoff of the whole `PaymentTerminal` interface, and it
lands much harder if the audience watched you build against the simulator first.

## Next

The card stack isn't here — this template is UI only. Build it in
`../template/` following `../../book/brewer-book.pdf`, then merge the two, or
add the `hardware/` package to this project as you go.
