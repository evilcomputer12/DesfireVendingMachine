# flutter_topup — DESFire EV2/EV3 NFC top-up

A Flutter Android app that reads and credits a balance stored in a **MIFARE
DESFire EV2/EV3 value file** over NFC, using AES-128 EV2 secure messaging.

It is the phone-side counterpart to the C firmware in this repository. The
crypto and secure-messaging layer is a direct port of
`plainc-desfire-rc522-main/lib/desfire/desfire_crypto.c` and `desfire_cmd.c`;
the value-file commands are new, because the C library does not have them.

---

## ⚠️ READ THIS BEFORE YOU DO ANYTHING WITH REAL MONEY

**This app ships hardcoded AES key material inside the APK.** The keys in
`lib/app_config.dart` are the fixed development keys from
`nucleof411re/Core/Src/desfiire.c`:

| Key | Value | Used for |
|---|---|---|
| Application master key (key 0) | `11 11 … 11` | provisioning the application and value file |
| Application user key (key 2) | `22 22 … 22` | reading and crediting the balance |

An APK is not a secret. Anyone with the file can run `strings`, `apktool`, or a
debugger and recover those 16 bytes in minutes. Because **the same key is on
every card**, recovering it once means being able to mint unlimited balance on
the entire fleet, forever, offline. There is no revocation story: you would
have to re-key every card by hand.

**This build is for a bench rig with disposable test cards. Do not deploy it.**

### What a real deployment does instead

Three things, and you need all three:

**1. Key diversification.** No card carries the master key. Each card's key is
derived from a per-card input — normally
`K_card = CMAC(K_master, UID ‖ AID ‖ label)`, per NXP AN10922. The master key
never leaves the secure element. Compromising one card yields one card's key,
which is worth exactly that card's balance. This is a bounded loss instead of
an unbounded one.

**2. The master key lives in an HSM or SAM, not in software.** A SAM (Secure
Access Module — e.g. MIFARE SAM AV3) is a smartcard that holds the master key
and performs the diversification and the DESFire authentication internally. It
answers "authenticate this card" but will never answer "what is the key". In a
backend deployment the equivalent is a network HSM behind an authenticated
service.

**3. Top-up happens against the backend, not on the phone.** The phone becomes
a dumb NFC pipe: it relays APDUs between the card and a server that holds the
keys and performs the credit. The server is where the money moves, so that is
where the audit log, the balance ledger, the rate limiting and the fraud
detection live. The phone is untrusted by design — it is someone's rooted
Android device.

The reason this matters more for **credit** than for **debit** is asymmetry: a
compromised debit key lets an attacker spend the money that is already on their
own card. A compromised credit key lets them create money. Which is why real
systems will happily put a debit key on a vending machine in a corridor, and
will never put a credit key on anything that leaves the back office.

### Other things this build does not do

- **No card authentication to the reader beyond the app key.** There is no
  check that this is *your* card rather than a clone with the same key.
- **No transaction log.** A real system reconciles the on-card balance against
  a server-side ledger to detect rollback attacks (where an attacker snapshots
  a card's state, spends, then restores the snapshot). DESFire EV2's
  Transaction MAC feature exists for exactly this; the code has a hook for it
  (`commitTransaction(returnTransactionMac: true)`) but the app does not use it.
- **No secure storage.** Nothing is persisted, so there is nothing to protect
  at rest, but there is also no offline audit trail.

---

## Running it

Requires the Flutter SDK (developed against Flutter 3.44 / Dart 3.12) and an
Android device with NFC. There is no emulator story — NFC needs real hardware.

```bash
cd flutter_topup
flutter pub get
flutter test            # 200 unit + widget tests, no hardware needed
flutter analyze
flutter run             # with an Android device attached
```

### Provisioning a blank card

A card that has never been personalised has no application on it, so
`SelectApplication(0x010203)` comes back `0xA0` (APPLICATION_NOT_FOUND). The
app turns that into a distinct "this card is not set up" state on the home
screen rather than an error, with a **Set up this card** button leading to
`SetupCardScreen`.

`CardGateway.provisionCard()` does the work. It is a port of steps **2 to 7**
of `df_setup_desfire` in the C library:

1. `SelectApplication(0x000000)` — drop to card level.
2. Authenticate with the factory PICC master key (16 zero bytes). The key type
   varies by card generation, so `authenticatePiccFactory` tries the legacy D40
   handshake (0x0A), then 3K3DES ISO (0x1A), then AES (0x71), mirroring
   `_auth_picc_factory`.
3. `CreateApplication(0xCA)` with `[aid, 0xEF, 0x80 | numKeys]` — `0x80` selects
   AES application keys.
4. Select the new application and authenticate with its default all-zero key.
5. `ChangeKey(0xC4)` for the user key, then the master key, with the C's
   re-select / re-authenticate between them (ChangeKey on the authenticated key
   makes the card drop the session, so this is load-bearing).
6. `CreateValueFile(0xCC)`.
7. Read the balance back to prove it worked.

**Step 1 of `df_setup_desfire` is deliberately not ported.** That step is
`df_full_format`, which sends `FormatPICC` (0xFC) and erases every application
and file on the card. This app gets pointed at whatever the user taps — an
office badge, a hotel key, a transit card, all DESFire and all
indistinguishable from a blank card until you look — so provisioning here is
strictly additive and there is no 0xFC anywhere in `lib/`. A test asserts that.
The PICC master key is likewise only read, never changed, so a card this app
touched can still be re-personalised by any other tool.

Provisioning is never automatic. The user has to press a button and then
confirm a dialog that names the consequences. `AppConfig.autoProvision`
(default `false`) skips only the confirmation dialog, for bench sessions.

Every step is idempotent: `CreateApplication` treats `0xDE` (DUPLICATE_ERROR)
as success, and each key is only written if the slot still holds the default.
Running it on a fully provisioned card just re-reads the balance; running it on
a card whose application exists but whose value file is missing creates only
the file.

`CardGateway.provisionValueFile()` still exists for the narrower case of an
application that already carries this app's keys but has no value file.

Card layout the app expects:

| Item | Value | Source |
|---|---|---|
| AID | `0x010203` | `DESFIRE_APP_ID` in `desfiire.c` |
| User key number | `2` | `app->df.userKeyNo` |
| Value file number | `0x01` | new — `0x02` is taken by the firmware's data file |
| Comm mode | Full (encrypted + CMAC) | matches the C write-data path |
| Limits | 0 … 100000 cents | `AppConfig` |

---

## How it works

### Authentication — `AuthenticateEV2First` (0x71)

1. Send `[keyNo, 0x00]`. The card returns `E(K, RndB)`.
2. Decrypt `RndB`, generate `RndA`, send `E(K, RndA ‖ RndB')` where `RndB'` is
   `RndB` rotated left one byte.
3. The card returns `E(K, TI ‖ RndA' ‖ caps)`. Verify `RndA'` matches, keep
   `TI`.
4. Derive the two session keys with `CMAC(K, SV)`, where SV is the 32-byte
   vector laid out in `deriveSessionKey` — `0xA5` for the encryption key, `0x5A`
   for the MAC key.

The command counter starts at 0 and increments after each accepted command.

### Secure messaging

| Element | Construction |
|---|---|
| Command CMAC | `truncate(CMAC(K_mac, [cmd][ctrLo][ctrHi][TI][data]))` |
| Response CMAC | `truncate(CMAC(K_mac, [0x00][ctrLo][ctrHi][TI][data]))` |
| Truncation | odd-indexed bytes: `mac[1], mac[3], … mac[15]` |
| Command IV | `AES-ECB(K_enc, [A5 5A][TI][ctrLo][ctrHi][00 × 8])` |
| Response IV | `AES-ECB(K_enc, [5A A5][TI][ctrLo][ctrHi][00 × 8])` |
| Padding | ISO 7816-4: `0x80` then zeros to the next 16-byte boundary |

The counter is incremented **after** the card accepts a command and **before**
the response MAC is verified, so the response MAC and response IV both use the
incremented value. This ordering is copied from the C and is load-bearing —
getting it wrong produces a CMAC mismatch on every response.

### Value files, and the one thing that matters

A value file stores a 4-byte **signed little-endian** integer, here interpreted
as euro cents.

**`Credit` and `Debit` do not change the balance.** They stage a delta in the
card's transaction buffer. The change becomes permanent only when
`CommitTransaction` (0xC7) succeeds. If the card leaves the field first, or
`AbortTransaction` (0xA7) is sent, the delta is discarded and the balance is
exactly what it was.

The code enforces this rather than trusting it:

- `DesfireCard.topUp()` sequences read → credit → commit → **read back**, and
  reports the balance it read *after* the commit. "The card said OK" and "the
  money is on the card" are not the same claim, and only the second one is
  reported to the user.
- If anything fails before the commit, `AbortTransaction` is attempted.
- The UI never shows a new balance until `topUp()` returns, and the scan sheet
  tells the user that moving the card mid-write charges nothing.

The test suite pins this down with a fake card that models the transaction
buffer: `Credit alone does NOT change the balance`, `losing the field before
commit discards the credit`, and `CommitTransaction is what makes the credit
permanent`.

Commands implemented: `CreateValueFile` (0xCC), `GetValue` (0x6C), `Credit`
(0x0C), `Debit` (0xDC), `LimitedCredit` (0x1C), `CommitTransaction` (0xC7),
`AbortTransaction` (0xA7).

---

## Layout

```
lib/
  app_config.dart              AID, file/key numbers, BENCH KEYS
  desfire/                     protocol layer — no Flutter, no NFC, fully testable
    desfire_crypto.dart        AES-ECB/CBC/CMAC, session keys, IVs, padding
    desfire_session.dart       session state + command/response MAC + IV
    desfire_card.dart          Transceiver interface + command layer
    desfire_exceptions.dart    typed errors mirroring DFStatus + status decoder
    value_file.dart            value encode/decode, access rights, file settings
  nfc/                         the only code that knows NFC exists
    iso_dep_transceiver.dart   Transceiver over Android IsoDep
    nfc_service.dart           one-shot awaitable polling session
    card_gateway.dart          app-level operations (read / top up / spend)
  ui/                          dark Material 3 kiosk UI
test/
  desfire_crypto_test.dart     RFC 4493 + FIPS 197 + C-derived vectors
  desfire_session_test.dart    MAC/IV/counter known-answer tests
  value_file_test.dart         encoding, access rights, parsing, status decoding
  desfire_card_test.dart       command layer end-to-end against a fake card
  fake_card.dart               software DESFire EV2 with a transaction buffer
```

The split is deliberate: `lib/desfire/` talks to an abstract `Transceiver`, so
the entire protocol — including the crypto, the framing, and the transaction
semantics — runs in unit tests with no phone and no card.

## Tests

125 tests, all passing, no hardware required. Two independent sources of truth:

1. **Published vectors.** AES-CMAC against the RFC 4493 test vectors (including
   the empty-message case), AES-128 against the FIPS 197 vector. These prove
   the primitives are standard.

2. **Vectors generated from the C library.** The project's own
   `desfire_crypto.c` was compiled and run to produce expected values for
   session-key derivation, command/response MAC construction, MAC truncation,
   IV construction at several counter values, and a `Credit` cryptogram. These
   prove the Dart port is byte-identical to the code the cards were
   personalised with — which matters more than matching the datasheet, since a
   divergence there would silently break every card in the field.

On top of that, `desfire_card_test.dart` runs the real command layer against a
software card that implements the protocol independently, checks the client's
CMACs, and models the value-file transaction buffer.

## What is and is not verified

| Check | Status |
|---|---|
| `flutter analyze` | clean |
| `flutter test` | 125/125 passing |
| `flutter build apk --debug` | builds |
| `flutter build apk --release` | builds (47.9 MB) |
| Manifest + tech filter compiled by `aapt2` | verified in the built APK |
| Release permissions | `NFC` only — no `INTERNET` |
| **Run on a phone** | **never** |
| **Tested against a real DESFire card** | **never** |

The bottom two rows are the important ones. Everything above them says the
code is well-formed and self-consistent; none of it says the card will
actually accept these commands. The inferences in "Protocol details taken
from the spec" below are exactly what a first run against real hardware will
confirm or demolish — expect to spend your first session there, and check
those items first when something fails.
