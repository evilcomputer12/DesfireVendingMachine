# Implementing DESFire Debit/Credit with the RC522

A guided build, not a copy-paste. Every step tells you what the card expects
and why, then leaves the code to you. Where a decision has real consequences,
you get the trade-off rather than a verdict.

You have already done the hard part once: your `plainc-desfire-rc522` repo
drives an RC522 through ISO-14443-4 block framing in software, which the chip
has no hardware support for. This document is about the layer above that —
turning a working secure channel into a payment system that cannot be tricked
into giving away free coffee.

---

## 0. Where this sits

```
┌─────────────────────────────────────────────────────────┐
│  UIController          — screens, animation, no crypto  │
├─────────────────────────────────────────────────────────┤
│  PaymentTerminal       ← the interface the UI depends on │
│    ├── SimulatedPaymentTerminal   (done, works today)   │
│    └── DesfirePaymentTerminal     ← YOU WRITE THIS      │
├─────────────────────────────────────────────────────────┤
│  DesfireSession        — auth state, CMAC, IV, counter  │
│  ValueFile             — GetValue / Debit / Commit      │
├─────────────────────────────────────────────────────────┤
│  Iso14443Transceiver   ← the interface hiding the RC522  │
│    └── Rc522Transceiver           (port from your C)    │
├─────────────────────────────────────────────────────────┤
│  HardwareBus           — SPI via Pi4J                   │
└─────────────────────────────────────────────────────────┘
```

The rule that makes this worth doing: **each layer may only talk to the one
directly below it.** If `UIController` ever imports something from the `card`
package beyond an exception type, a layer has leaked and you should push back
on yourself about why.

---

## 1. Why a value file and not a data file

You could store a balance as four bytes in a standard data file and use
`WriteData` (0x3D), which your C library already implements. Do not. Here is
the difference that matters:

A **standard data file** write is just a write. If the card leaves the RF
field halfway through, you get a partial write and a corrupted balance. There
is no undo.

A **value file** is backed by the card's transaction mechanism. `Debit`
(0xDC) does not change the stored value — it stages the change in a
transaction buffer. The value only becomes real when `CommitTransaction`
(0xC7) succeeds. Lose power in between and the card discards the change on
next power-up. The balance is never left in a half-changed state.

For money on a card that gets yanked out of an RF field constantly by
impatient people holding coffee, that atomicity is the entire ballgame.

Value files also give you, for free:
- **Upper and lower limits** enforced by the card, not by your code
- **Signed 4-byte arithmetic** done on-card
- **LimitedCredit** — refund up to what was debited this session, without
  needing the key that can add arbitrary money

> **Exercise 1.** Your value file has `LowerLimit = 0`. A user with $1.20
> tries to buy a $3.50 mocha and you send `Debit(350)` anyway. What status
> byte comes back, and at what point — the Debit, or the Commit? Predict
> first, then test on a real card. The answer shapes where your error
> handling has to live.

---

## 2. The security model — read this before writing any code

### 2.1 A UID is not an identity

The UID is transmitted in the clear during anticollision, before any
authentication. It is trivially readable by any $8 reader and trivially
emulated by any Android phone with an HCE app — including the one you were
planning to write.

So: **never** make a spending decision based on a UID. The UID is a database
key for looking up which diversified key to use, and nothing more. The moment
you write `if (uid.equals(KNOWN_GOOD_UID))` you have built something a
teenager can defeat in an afternoon.

Identity comes from one place only: the card proving it holds the right AES
key, via `AuthenticateEV2First`. That handshake is mutual — the card also
verifies *you*, which is what stops a fake reader from harvesting balances.

### 2.2 Key diversification

Do not put the same AES key on every card. One extracted card would then
compromise every card in the system.

Instead derive a per-card key from a master key and the card's UID:

```
cardKey = AES-CMAC(masterKey, diversificationInput(UID, AID, ...))
```

NXP specifies this in **AN10922**. Follow it rather than inventing your own
derivation — the input encoding details matter.

Now an attacker who fully compromises one card learns one card's key. To mint
money at scale they need the master key.

### 2.3 So where does the master key live?

This is the uncomfortable part, and the honest answer is: **not in your
kiosk, and not in your phone app.**

Your Pi sits in a hallway. Anyone can unplug it, take the SD card, and read
the filesystem. Any key on that SD card is a key an attacker has. Same for
the Flutter top-up app — an APK is a zip file, and strings inside it are not
secret.

Real deployments solve this with a **SAM** (Secure Access Module — a smartcard
in the reader that performs crypto without exposing keys) or by keeping
top-up server-side, where the phone is only a network pipe to a backend HSM
that holds the keys.

For a learning project, hardcoded keys are fine — but know that you are
building the bench version, and write it down in your README so future-you
does not deploy it. **The Flutter app in this repo ships with a bench key and
says so loudly.**

### 2.4 The access-rights design that saves you

This is where you get real security for free. A value file's access rights
are four 4-bit key numbers packed into two bytes:

| Nibble | Right |
|---|---|
| 15..12 | Read |
| 11..8 | Write |
| 7..4 | ReadWrite |
| 3..0 | Change |

Values `0x0`–`0xD` are key numbers, `0xE` = free access, `0xF` = never.

**Which right permits which command is the part you must look up.** For value
files the mapping is not the obvious one — several commands are satisfied by
more than one right, and `Credit` in particular is commonly documented as
requiring **ReadWrite** rather than Write. The Flutter top-up app in this repo
assumes `read → GetValue`, `write → Debit/LimitedCredit`,
`readWrite → Credit`; that assumption is called out in its README because it
was inferred from the spec, not measured against a card.

I am deliberately not giving you a table here. Two independent readings of
the spec produced two different mappings while this project was being built,
which tells you exactly how easy it is to get wrong from memory or a forum
post. Open the MF3D(H)x3 datasheet, find the value-file command table, and
write the mapping down yourself — then confirm it empirically on a test card
before any real card gets provisioned.

**Give the kiosk a key that can Debit but cannot Credit.** Put Credit behind
a different key number that only the top-up station holds. Then the worst
case for a stolen, fully-reverse-engineered kiosk is an attacker who can take
money *off* cards — annoying, but not counterfeiting.

> **Verify before you commit.** The exact mapping of which right permits
> which command (particularly whether Debit is satisfied by Read, Write, or
> ReadWrite) is specified in the MF3D(H)x3 datasheet, and getting it wrong
> means either your kiosk cannot debit at all, or you shipped a key that can
> print money. Check the table in the datasheet — do not take my word or
> anyone's forum post for it.

> **Exercise 2.** Sketch the four nibbles for your wallet file. Which key
> number does the kiosk get? Which does the top-up station get? What goes in
> the Change nibble, and why is `0xE` (free) catastrophic there?

---

## 3. The protocol, command by command

Payloads below are the DESFire native layer — what goes into `_send_cmd()`
in your C code, before ISO-DEP framing.

### CreateValueFile — `0xCC` (one-time provisioning)

17-byte payload:

| Field | Bytes | Notes |
|---|---|---|
| FileNo | 1 | e.g. `0x01` |
| CommSettings | 1 | **`0x03` = fully enciphered.** Use this. |
| AccessRights | 2 | little-endian, section 2.4 |
| LowerLimit | 4 | signed LE — `0` for a wallet |
| UpperLimit | 4 | signed LE — cap it, e.g. `10000` = $100 |
| InitialValue | 4 | signed LE |
| LimitedCreditEnabled | 1 | `0x01` to allow session refunds |

`CommSettings = 0x00` (plain) means the amount crosses the RF gap in
cleartext. Anyone with an SDR sees every transaction. There is no reason to
ever use plain mode for money.

### GetValue — `0x6C`

Payload: `FileNo` (1). Returns a 4-byte signed LE value, encrypted and MACed
under the session keys when the file is `COMM_FULL`.

### Debit — `0xDC`

Payload: `FileNo` (1) + amount (4, signed LE), enciphered.

**Does not change the balance yet.** Stages it.

### Credit — `0x0C`

Same shape as Debit. Requires the Credit right — deliberately not available
to your kiosk.

### CommitTransaction — `0xC7`

No payload. **This is the command that actually moves the money.** Only after
this returns `0x00` may you dispense.

### AbortTransaction — `0xA7`

No payload. Explicitly discards staged changes. Send it if you decide to bail
after a Debit but before a Commit.

### Status bytes you will actually hit

| Byte | Meaning | Usually means |
|---|---|---|
| `0x00` | OPERATION_OK | |
| `0xAF` | ADDITIONAL_FRAME | more data coming, not an error |
| `0xBE` | BOUNDARY_ERROR | debit would breach the lower limit |
| `0x9D` | PERMISSION_DENIED | wrong key for this right — check §2.4 |
| `0xAE` | AUTHENTICATION_ERROR | not authenticated, or session died |
| `0x1E` | INTEGRITY_ERROR | CMAC/CRC failed — treat as hostile |

---

## 4. What your C library already gives you

I read through `lib/desfire/desfire_cmd.c`. You are further along than you
might think. Already working:

- `_send_cmd()` — APDU build, send, status handling, `0xAF` chaining
- `_calc_cmd_mac()` — CMAC over `[cmd, ctr_lo, ctr_hi, TI(4), data...]`,
  truncated to 8 bytes
- `_verify_resp_mac()` — same over `[status, ctr_lo, ctr_hi, TI(4), data...]`
- `_calc_iv_cmd()` — `AES-ECB(sessKeyEnc, [0xA5,0x5A,TI,ctr_lo,ctr_hi,0×8])`
- `_calc_iv_resp()` — same with the `0x5A,0xA5` prefix
- `df_authenticate_ev2_first()` — full AES-128 EV2 handshake, derives
  `sessKeyEnc` (label `0xA5`) and `sessKeyMac` (label `0x5A`), zeroes
  `cmdCounter`
- `df_select_application()`, `df_write_data()`, `df_read_data()`

**Missing — and it is exactly the payment layer:** every value-file command
in section 3. `desfire_cmd.h` has no `df_credit`, `df_debit`, `df_get_value`,
or `df_commit_transaction`.

The good news: the hard parts (session derivation, CMAC, IV chaining) are
already solved and tested. Adding `Debit` is mostly plumbing on top of
`_send_cmd()` and `_calc_cmd_mac()` — the same shape as `df_write_data()`,
with a different command byte and a 4-byte payload.

> **Exercise 3.** Open `df_write_data()` in `desfire_cmd.c` and trace exactly
> which helpers it calls and in what order. `Debit` follows the same
> sequence. Write that sequence out as a numbered list before you write a
> line of Java — then your Java is a translation exercise rather than a
> design exercise.

---

## 5. Mapping this onto the OOP concepts you are studying

This project is a genuinely good vehicle for the syllabus because each
concept earns its place rather than being decoration:

| Concept | Where it does real work here |
|---|---|
| **Interface** | `PaymentTerminal` lets the UI run against a simulator today and hardware tomorrow with a one-line change. `Iso14443Transceiver` is the same trick one layer down. |
| **Encapsulation** | `DesfireSession` owns `sessKeyEnc`, `sessKeyMac`, `TI`, `cmdCounter`. Nothing outside can touch the counter — because a desynced counter silently breaks every CMAC afterwards. |
| **Abstract class** | An `AbstractDesfireCommand` holding the build → MAC → send → verify → increment skeleton, with subclasses supplying only the command byte and payload. This is the Template Method pattern. |
| **Inheritance** | `CardException` → `AuthenticationFailedException`, `CmacMismatchException`, … Catch the base to abort; catch a leaf to react specifically. |
| **Polymorphism** | `UIController` calls `terminal.startTransaction(...)` without knowing which implementation answers. |
| **Composition** | `DesfirePaymentTerminal` *has an* `Iso14443Transceiver`, which *has a* `HardwareBus`. Prefer this over inheriting hardware behaviour — a terminal is not a kind of SPI bus. |
| **Exceptions** | Replace `DFStatus` return codes. The compiler forces callers to handle them; a returned `-3` can be ignored silently. |
| **Collections** | `DrinkCatalog`'s `LinkedHashMap` — O(1) lookup plus stable menu order. |
| **Immutability** | `Drink` and `PaymentReceipt` are final with no setters, so a price cannot change mid-transaction. |

---

## 6. Build order

Do these in sequence. Each step is independently testable, which means when
something breaks you know which step broke it.

### Step 1 — `Rc522Transceiver implements Iso14443Transceiver`

Port `platform_activate_card()` and `platform_send_apdu()` from
`desfiire.c`. Use Pi4J for SPI.

Remember DESFire has a 7-byte UID, so `SAK & 0x04` is set and you must run
**both** anticollision cascade levels — your C code already handles this
(`PICC_ANTICOLL2` / `PICC_SElECTTAG2`).

**Done when:** you can print a card's UID and a `GetVersion` (0x60) response.
No crypto yet. If this step is flaky, everything above it will be flaky in
ways that look like crypto bugs — do not move on until taps are reliable.

### Step 2 — `DesfireSession`

Port `df_authenticate_ev2_first()`. Keep `sessKeyEnc`, `sessKeyMac`, `ti`,
`cmdCounter` private, and expose `calculateCommandMac()`,
`verifyResponseMac()`, `encrypt()`, `decrypt()`.

Java gives you AES via `javax.crypto.Cipher` (`"AES/CBC/NoPadding"`,
`"AES/ECB/NoPadding"`). AES-CMAC is **not** in the JDK — you will need
BouncyCastle (`org.bouncycastle.crypto.macs.CMac`) or to implement RFC 4493
yourself. Implementing it is a genuinely instructive afternoon; the subkey
generation is the interesting bit.

**Done when:** a known key + known RndA/RndB reproduce the same session keys
your C code produces. Test this with fixed vectors before going near a card —
debugging crypto over RF is miserable.

### Step 3 — `ValueFile`

`getValue()`, `debit()`, `commit()`, `abort()`. Each is: build payload →
CMAC → (encrypt if `COMM_FULL`) → send → verify response MAC → increment
counter.

**Done when:** you can read a balance off a provisioned card.

### Step 4 — `DesfirePaymentTerminal`

Wire it into the `PaymentTerminal` interface. Poll on a background thread;
deliver callbacks; let `UIController` handle the `Platform.runLater` hop
(it already does).

### Step 5 — flip the switch

```java
// UIController#createTerminal()
return new DesfirePaymentTerminal(new Rc522Transceiver(SpiBus.open()));
```

If the UI needs any other change, something leaked. Fix the abstraction.

---

## 7. The torn transaction problem

This is the part most tutorials skip, and it is the part that decides whether
your system is honest.

Between `Debit` and `CommitTransaction`, the card can leave the field. Walk
the cases:

| Card leaves… | Card state | What you must do |
|---|---|---|
| before `Debit` | unchanged | nothing; decline |
| between `Debit` and `Commit` | change discarded on next power-up | decline, do not dispense |
| after `Commit` **response received** | committed | dispense |
| after `Commit` **sent, response lost** | **unknown** | ← the hard one |

That last row has no clean local answer. You sent the commit; you never
learned whether the card processed it. If you dispense, you may be giving
away a free drink. If you refuse, you may have charged someone for nothing —
which is far worse for trust.

Your options, roughly in order of how much infrastructure they need:

1. **Fail closed, reconcile on next tap.** Log the ambiguous transaction
   locally. Next time that UID appears, read the balance and compare against
   what you expected. Cheap, and correct eventually — but the user has
   already walked away annoyed.

2. **DESFire EV2 Transaction MAC.** Configure the file with a Transaction MAC
   and the card maintains a monotonic transaction counter (TMC) plus a MAC
   (TMV) over each committed transaction. On the next tap you can prove
   whether the commit landed. This is what the feature exists for.

3. **Online reconciliation.** Kiosk reports transactions to a backend; a
   nightly job resolves discrepancies against the top-up records. What real
   transit systems do.

For a hallway coffee machine, (1) is defensible. Just make it a *decision*
you took knowingly, not a case you never considered.

> **Exercise 4.** Which failure do you prefer: occasionally giving away a
> free coffee, or occasionally charging for a coffee that never came out?
> There is a right answer for a coffee machine and a different right answer
> for a train barrier. Say which you chose and why, in a comment at the top
> of your commit handler.

---

## 8. Testing without hardware

Do not debug crypto over an RF link. Split it:

**Unit-testable with no card at all** — and this is most of the value:
- Session key derivation against fixed RndA/RndB
- CMAC against the RFC 4493 test vectors
- IV calculation for a known TI and counter
- Value encode/decode round-trips (`DesfireCommands.encodeValue`)
- Command payload construction, asserted byte for byte

**Needs a card:** anticollision, RATS, the live handshake, timing.

The trick that makes this pleasant: `Iso14443Transceiver` is an interface, so
write a `ReplayTransceiver` that returns canned responses captured from your
STM32 logs. Your entire DESFire stack then runs, and is debuggable, on your
laptop with no Pi and no card.

> **Exercise 5.** Your `desfiire.c` already logs every APDU
> (`[NFC] APDU TX len=… ins=0x…`). Capture one full successful session over
> UART, and turn that transcript into a `ReplayTransceiver` fixture. You now
> have a regression test for the whole stack.

---

## 9. Provisioning a card (run once, from a setup tool — not the kiosk)

1. `SelectApplication(0x000000)` — the PICC level
2. Authenticate with the PICC master key
3. `CreateApplication(0x010203, numKeys)` — enough keys for kiosk + top-up
4. `SelectApplication(0x010203)`
5. Authenticate with the app master key
6. `ChangeKey` for each role, using **diversified** keys (§2.2)
7. `CreateValueFile(0xCC)` with the access rights from §2.4
8. Verify with `GetFileSettings` (0xF5) and `GetValue` (0x6C)

Keep this in a separate tool. The kiosk binary should not contain the code
paths to create applications or change keys — it has no business being able
to, and a kiosk that *can* reformat cards is a kiosk that *will*, the first
time you get a state machine wrong.

Steps 1–6 map closely onto `df_setup_desfire()`, which you already have.

---

## 10. Reading

- **MF3D(H)x3 datasheet** (DESFire EV3) — the command and access-rights
  tables. The authority; check it rather than forum posts.
- **AN12343** — DESFire EV2/EV3 features, including Transaction MAC.
- **AN10922** — key diversification. Follow it exactly.
- **RFC 4493** — AES-CMAC, with test vectors you can assert against today.
- **ISO/IEC 14443-4** — the block framing you already implemented in C.
- `desfire-9f122c71e0057d4f747d2ee295b0f5f6eef8ac32.html` in this repo — your
  captured EV1 session logs, useful as replay fixtures.

---

## Checklist

- [ ] Kiosk holds a key that can Debit but **not** Credit
- [ ] Keys are diversified per card (AN10922)
- [ ] Value file is `COMM_FULL` (`0x03`), never plain
- [ ] `onApproved` fires **only** after `CommitTransaction` returns `0x00`
- [ ] Balance check happens before Debit, for a useful error message
- [ ] Torn-transaction policy chosen deliberately and written down
- [ ] CMAC verified on every response; mismatch tears down the session
- [ ] No spending decision anywhere keys off a UID
- [ ] Crypto has fixed-vector unit tests that run without hardware
