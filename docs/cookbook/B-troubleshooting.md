# Appendix B — The bug museum

Every trap in this book, indexed by **what you actually see**. These are real bugs from the real build, not hypotheticals.

---

## How to debug this stack at all

Before the tables — the method, which matters more than any individual fix.

**1. Bisect by layer.** The stack has six layers and two seams. Find which layer is lying:

```
version() == 0x92 ?          → SPI + driver are fine (Ch. 1-2)
isCardPresent() true ?       → transceive + init are fine (Ch. 3)
activate() returns a UID ?   → anticollision + CRC are fine (Ch. 5)
GetVersion returns 91AF ?    → ISO-DEP framing is fine (Ch. 6)
vectors green ?              → crypto is fine (Ch. 7-10)
```

The first `no` is your bug. Do not debug above a layer that hasn't answered `yes`.

**2. Crypto never gets debugged on hardware.** If a card operation fails, run the offline vectors first. Green vectors mean the bug is in framing, state, or the card's configuration — not in your maths. That single distinction saves hours.

**3. Print the MAC *input*, not the MAC.** When a MAC mismatches, the output tells you nothing (it's noise either way). The input tells you everything — you can see immediately whether the counter, the TI, or the data is wrong.

**4. Suspect the counter first.** In secure messaging, roughly nine failures out of ten are the command counter being off by one, not the cryptography.

---

## Hardware and SPI (Chapters 1–2)

| You see | Cause | Fix |
|---|---|---|
| `version()` = `-110` | Signed byte | `& 0xFF` |
| `version()` = `0x00` | MISO stuck low | Power (3.3 V!), wiring, CS pin |
| `version()` = `0xFF` | MISO floating | MISO not connected |
| Version flickers between runs | Wire length / noise | **Shorten the jumpers.** Not a code bug. |
| Reads fine, writes ignored | Read bit `0x80` set on writes | Check the address encoding |
| `NoClassDefFoundError: com/sun/jna` | JNA missing | `mvn dependency:copy-dependencies` |
| `No gpio chip found` in a **unit test** | Test constructs real hardware | You inherited instead of composing — see below |
| Second run fails, first worked | Device file still locked | `close()` / try-with-resources |
| `Permission denied` on `/dev/spidev0.0` | Not in the `spi` group | `sudo usermod -aG spi,gpio $USER` |

> **The `extends Pi4jSpiLink` disaster.** Writing `class Rc522Driver extends Pi4jSpiLink` compiles and looks convenient. Then every unit test constructs a Pi4J context, hunts for a GPIO chip, and dies on your laptop. You've welded the driver to the hardware and destroyed the seam. **A driver HAS-A link; it is not a kind of link.**

---

## Card detection and activation (Chapters 3, 5)

| You see | Cause | Fix |
|---|---|---|
| Program runs for **minutes** | Ported C's iteration counter | Wall-clock `System.nanoTime()` deadline |
| `0/15` polls, version fine | Init sequence incomplete | `TxASKReg=0x40`, `ModeReg=0x3D` |
| Detects once, then blind forever | Card sits in HALT | Send WUPA (`0x52`) after REQA |
| Detection ~50 % | Card position / wires | Flat on the antenna, short wires |
| 7-byte UID, first byte wrong | Kept the `0x88` cascade tag | Skip it: use `ac1[1..3]` |
| `select()` returns −1 | CRC wrong | `ModeReg = 0x3D` (preset `0x6363`) |
| `SAK = 0x08` | It's a MIFARE Classic | Wrong card — you need DESFire |
| RATS empty | Not 14443-4, or RATS sent twice | Check SAK bit 5; don't re-RATS |
| Works, then fails on retry | Card left ACTIVE | `softReset()` + `antennaOn()` per tap |
| `negative array size` | FIFO level read too early | Check the IRQ wait loop |

---

## ISO-DEP and APDUs (Chapter 6)

| You see | Cause | Fix |
|---|---|---|
| Empty reply | Block number desynced | Toggle it once per exchange |
| Works exactly once per power-up | Never toggling | `blockNumber ^= 1` |
| Reply one byte too long | PCB not stripped | `copyOfRange(reply, 1, …)` |
| `6E 00` / `6D 00` | ISO-7816 error, not DESFire | Check CLA `0x90` and the trailing `0x00` |
| `91 AE` on a command that used to work | Card mid-chain from `91 AF` | Drain the chain or reset the field |
| GetVersion byte 3 is `0x12` | It's an EV1 | EV1 has no `AuthenticateEV2First` |

---

## Crypto (Chapters 7–10)

| You see | Cause | Fix |
|---|---|---|
| CMAC empty-message vector fails only | Missing the `n=1, lastComplete=false` case | Special-case empty |
| CMAC partial-block vectors fail | Padding wrong | `0x80` then zeros — not all zeros |
| CMAC full-block vectors fail | K1/K2 swapped | Full = K1, padded = K2 |
| **All** CMAC vectors fail plausibly | `leftShift` carry direction | Walk **backwards**, last byte first |
| Both session keys wrong | SV layout | Print the SV, compare to the spec |
| Session keys swapped | Labels reversed | `0xA5` = enc, `0x5A` = mac |
| `91 7E` on auth step 1 | Sent only `keyNo` | Must be `{ keyNo, 0x00 }` |
| Step-2 ciphertext ≠ vector | Two separate CBC calls | One **32-byte** CBC run |
| Auth "succeeds" always | You never compared `RndA'` | Actually throw on mismatch |
| Fake test decrypts garbage | Replies lack `91 xx` | Append the status word to fakes |
| **Auth OK, first `getValue` MAC fails** | **Session built without TI** | `new DesfireSession(key, rndA, rndB, ti)` |
| Command MACs right, response MACs wrong | Increment ordering | Increment **before** verifying the response |
| First command OK, second fails | Counter not incremented (or twice) | Exactly one per accepted command |
| Balance unchanged after debit | No commit | `commitTransaction()` — working as designed |
| `91 BE` | Boundary error | Debit breaches the file's lower limit |
| `91 9D` | Permission denied | Wrong key for that file's access rights |
| `91 F0` | File not found | Card not provisioned (Chapter 12) |
| Everything fails after one bad run | Card mid-transaction | Power-cycle the field, re-authenticate |

### The three crypto bugs that cost the most time

**1. The TI one-liner.** `authenticateEv2First` builds the session; if you pass only `(key, rndA, rndB)`, TI defaults to zeros. Authentication still succeeds — the TI isn't used until secure messaging — so the failure appears *one command later*, in code that is completely correct. Symptom: perfect auth, then an immediate response-MAC failure on `getValue`.

**2. Counter increment ordering.** The counter ticks **after the card accepts the command, before you verify the response.** Command MAC uses the old value, response MAC and response IV use the new one. Backwards, and every command MAC is right while every response check fails.

**3. Fake replies without status words.** Your card code strips two bytes off every reply. A fake returning bare ciphertext loses two real bytes, and the decrypt produces garbage that looks like a crypto bug.

---

## Tests and tooling

| You see | Cause | Fix |
|---|---|---|
| Test passes but proves nothing | The method is never called | Assert on a **return value** |
| Test passes, hardware fails | Fake too permissive | Make the fake assert what it received |
| Changed code, behaviour identical | Stale `.class` files | `mvn clean compile` |
| Test passes alone, fails in the suite | Shared mutable state between tests | Fresh session per `@Test` |
| Debit + getValue tests conflict | Same fake, different counters | Separate `@Test` methods |

> **The green-test-that-tests-nothing.** The most dangerous bug in this book, because it *rewards* you. You add a `case 0x6C` to your fake, run the suite, see green — and never notice the test body doesn't call `getValue`. Guard against it deliberately: after writing a test, ask *"what would I have to break to make this fail?"* If the answer is "nothing", it isn't a test.

---

## UI and deployment (Chapters 11, 13)

| You see | Cause | Fix |
|---|---|---|
| UI freezes on tap | Card work on the FX thread | Background worker |
| Intermittent rendering corruption | Node touched off-thread | `Platform.runLater` everywhere |
| Animations crawl | Software rendering | `-Dprism.forceGPU=true` |
| Touch inverted | Display rotated, touch not | Rotate the input device too |
| A tile stuck highlighted | Xwayland cursor sitting on it | `Cursor.NONE`, scope hover to desktop |
| Second tap always declines | Field not power-cycled | `softReset` + `antennaOn` per transaction |
| Kiosk dies overnight | Unhandled exception | Broad catch + `Restart=always` |
| Simulator used on the Pi | Constructor threw | Read the logged message — it names the cause |

---

## Environment oddities from the real build

Not bugs in your code, but they'll cost you time anyway:

- **Pi IP changes constantly** on DHCP. Set a static lease or use `raspberrypi.local`.
- **SSH host-key conflicts** after a DHCP reshuffle: `Password authentication is disabled to avoid man-in-the-middle attacks`. Fix the known-hosts entry rather than disabling checking permanently.
- **sshd dies under memory pressure** on a Zero 2 W with a JavaFX app running. Add swap, or don't run the kiosk while developing.
- **`git commit` hangs for minutes** on a folder synced by iCloud/Dropbox — the index refresh stalls on the network filesystem. Keep repos out of synced folders.
- **`timeout` isn't on macOS** by default. Use `gtimeout` (coreutils) or a background job.

---

*Next: [Appendix C — YouTube build plan](C-video-plan.md)*
