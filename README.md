# The Midnight Brewer

A DESFire-powered coffee vending machine, built as a vehicle for learning
object-oriented design against a problem that actually demands it.

- **Kiosk** — JavaFX touchscreen HMI on a Raspberry Pi with a 1024×600 panel
- **Reader** — RC522 driven through a software ISO-14443-4 stack, ported from
  the author's [`plainc-desfire-rc522`](nucleof411re/Core/Src) STM32 project
- **Wallet** — a DESFire EV2/EV3 value file, debited on tap
- **Top-up** — a Flutter Android app that credits cards over NFC

---

## Running the kiosk

```bash
mvn javafx:run                          # fullscreen kiosk (Pi)
mvn javafx:run -Dbrewer.windowed=true   # windowed, for laptop development
```

Press <kbd>Esc</kbd> to quit — an undecorated fullscreen window on a
touchscreen with no keyboard is otherwise hard to escape.

No card reader is needed to try it. The kiosk boots against
`SimulatedPaymentTerminal`, which carries a virtual $25.00 wallet; the
`simulate tap` button on the payment screen stands in for presenting a card.
Buy enough coffee and you will hit the insufficient-funds path.

---

## Screen flow

```
Selection ──tap drink──> Payment ──card approved──> Preparing ──> Result
    ▲                       │                                        │
    └───────────cancel──────┘                    auto-return after 5s ┘
```

Every selection and state change is logged to stdout with a `[UI]` prefix, so
you can follow the flow from the terminal while testing on the Pi.

---

## Layout

```
src/main/java/com/midnightbrewer/
  model/       Drink, DrinkCatalog          — the menu, data-driven
  card/        exceptions, DesfireCommands, Iso14443Transceiver
  payment/     PaymentTerminal + simulator + DESFire skeleton
  ui/          MainApp, UIController
src/main/resources/
  MidnightBrewer.fxml    screen scaffolding (tiles are generated, not declared)
  styles.css             all styling; no inline styles in the FXML
  images/*.png           drink artwork
docs/
  DESFIRE-PAYMENT-TUTORIAL.md    ← the guided build for card payment
scripts/
  fix-rotation.sh        display + touchscreen rotation for the Pi
  README-rotation.md
tools/
  generate_coffee_art.py generates the drink artwork
flutter_topup/           Android top-up app
```

---

## Adding a drink

One line. The grid, the payment screen and the result screen all read from
the catalog:

```java
// DrinkCatalog.java
add(new Drink("cortado", "Cortado", 275));
```

Then add artwork as `src/main/resources/images/cortado.png` — or define it in
`tools/generate_coffee_art.py` and re-run the generator, which keeps it
visually consistent with the rest of the menu.

---

## Artwork

`tools/generate_coffee_art.py` draws all ten drinks from scratch with Pillow:
same camera angle, same light direction, same margins, rendered at 4× and
downsampled. Silhouettes are deliberately distinct — a demitasse, a tall
glass, a whipped mocha and an iced cold brew are tellable apart at a glance
from across a room, which is the actual job of a vending machine tile.

```bash
python3 tools/generate_coffee_art.py
```

---

## Raspberry Pi display rotation

```bash
sudo ./scripts/fix-rotation.sh --status      # what's detected, nothing changed
sudo ./scripts/fix-rotation.sh --dry-run left
sudo ./scripts/fix-rotation.sh left
```

Rotating the display does **not** rotate the touchscreen — taps keep landing
in the original orientation. The script fixes both together, across labwc,
wayfire, X11 and KMS. See [`scripts/README-rotation.md`](scripts/README-rotation.md).

Rotation direction depends on how your panel is physically mounted; if the
image comes out backwards, re-run with `right` instead of `left`.

---

## Implementing real card payment

The kiosk talks only to the `PaymentTerminal` interface, so hardware slots in
by changing one line in `UIController#createTerminal()`:

```java
return new DesfirePaymentTerminal(new Rc522Transceiver(SpiBus.open()));
```

`DesfirePaymentTerminal` is left as a skeleton on purpose.
**[docs/DESFIRE-PAYMENT-TUTORIAL.md](docs/DESFIRE-PAYMENT-TUTORIAL.md)** walks
through the value-file protocol, the security model, the torn-transaction
problem, and a testable build order — without writing the crypto for you.

The single rule worth repeating here: a `Debit` is not real until
`CommitTransaction` returns `0x00`. Dispense before that and the machine
gives away free coffee.

---

## Security status

This is a **bench build**. Keys are hardcoded, which is fine on a desk and
not fine in a hallway — a Pi's SD card is readable by anyone who unplugs it,
and an APK is a zip file. Section 2 of the tutorial covers key
diversification (AN10922) and why production systems keep master keys in a
SAM or a backend HSM rather than on the reader.
