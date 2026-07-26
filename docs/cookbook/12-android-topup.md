# 12 — The Android top-up app

**Goal:** put credit *onto* the card from a phone. Build it yourself, in Dart, from the design you already understand.

**Concept:** porting a design across languages — and discovering how much of your stack was incidental.

---

## Why this chapter is different

You've built the whole reader stack once. This chapter you build the *other half of the system* — and the point is not to learn NFC again. It's to discover something valuable:

**Six of your ten chapters don't exist on Android.**

The phone has an NFC controller with a driver already written. Android's `IsoDep` class hands you `transceive(byte[]) → byte[]` directly. That single method is everything you built in Chapters 1–6:

| Your Pi stack | Android |
|---|---|
| Ch. 1 `SpiLink` | *gone* — the OS owns the radio |
| Ch. 2 `Rc522Driver` | *gone* |
| Ch. 3 `Rc522Reader` | *gone* |
| Ch. 4 `ProtocolBlock` | *gone* — the OS frames I-blocks |
| Ch. 5 activation | *gone* — Android hands you an activated tag |
| Ch. 6 `IsoDepChannel` | **`IsoDep.transceive()`** |
| **Ch. 7–10 crypto** | **you port all of it, unchanged** |

That asymmetry is the lesson. The transport was replaceable; **the protocol and the cryptography were the real work.** And you can see it directly: your `ApduChannel` interface from Chapter 6 has exactly the same shape as Android's `IsoDep.transceive`. That's not luck — it's what happens when you draw the seam at the right place. Everything above `ApduChannel` ports across a language, an OS, and a completely different radio.

> **Analogy.** You've been writing a letter and hand-delivering it. Now you're posting it. The envelope, the courier, the van — all different. The letter is identical.

---

## Setup

```bash
flutter create --org com.midnightbrewer --platforms android topup_app
cd topup_app
```

`pubspec.yaml`:

```yaml
dependencies:
  flutter:
    sdk: flutter
  # Android IsoDep (ISO 14443-4) transceive.
  # ⚠️ 4.x has a different API from 3.x -- pin the major version.
  nfc_manager: ^4.2.1
  # AES-ECB/CBC and AES-CMAC.
  pointycastle: ^4.0.0
```

`android/app/src/main/AndroidManifest.xml` needs:

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />
```

---

## Dart for a Java programmer

The languages are close cousins. Four differences that matter here:

**1. `Uint8List`, not `byte[]`.** From `dart:typed_data`. Crucially its elements are **unsigned 0–255** — Dart has no signed-byte trap. All those `& 0xFF` masks simply vanish.

**2. `int` is 64-bit.** So `1 << 40` works fine, but when you write a 32-bit value you must mask deliberately: `(v >> 24) & 0xFF`.

**3. Async is explicit.** NFC calls are `Future`s. A function that awaits must be `async` and its callers must `await` it — the `async`/`await` colouring propagates upward, much like `throws SpiException` did in Java. Same discipline, different keyword.

**4. No checked exceptions.** Dart has `throw`/`catch` but the compiler doesn't force you to handle anything. You lose the compiler's nagging, so you must be more deliberate about error paths.

---

## Build it

### The transport — one class replaces six chapters

`lib/nfc/iso_dep_transceiver.dart`:

```dart
import 'dart:typed_data';
import 'package:nfc_manager/nfc_manager.dart';

/// Sends one APDU to an activated card and returns the reply.
///
/// This is the ENTIRE transport layer -- the Android NFC stack has already
/// done activation, anticollision, RATS and ISO-DEP framing for us.
/// Compare Chapters 1-6 of the Pi build. Same seam, no work.
class IsoDepTransceiver {
  IsoDepTransceiver(this._isoDep);

  final IsoDep _isoDep;

  Future<Uint8List> transceive(Uint8List apdu) async {
    // TODO: return await _isoDep.transceive(data: apdu);
    //   Wrap failures in your own exception type so the UI never sees a
    //   platform exception -- the same translate() discipline as Chapter 11.
    throw UnimplementedError();
  }
}
```

### The crypto — port it directly

Your Java from Chapters 7–10 translates almost line for line. `pointycastle` supplies AES **and** CMAC, so you don't even reimplement RFC 4493 — but you must verify it produces the same bytes.

`lib/desfire/desfire_crypto.dart`:

```dart
import 'dart:typed_data';
import 'package:pointycastle/export.dart';

const int kAesBlockSize = 16;

Uint8List aesEcbEncryptBlock(Uint8List key, Uint8List block) {
  final engine = AESEngine()..init(true, KeyParameter(key));
  final out = Uint8List(kAesBlockSize);
  engine.processBlock(block, 0, out, 0);
  return out;
}

Uint8List aesCbcEncrypt(Uint8List key, Uint8List iv, Uint8List data) {
  // TODO: CBCBlockCipher(AESEngine())..init(true, ParametersWithIV(KeyParameter(key), iv));
  //       process block by block; NO padding (DESFire pads itself).
  throw UnimplementedError();
}

Uint8List aesCmac(Uint8List key, Uint8List message) {
  final mac = CMac(AESEngine(), 128)..init(KeyParameter(key));
  return mac.process(message);
}

/// DESFire keeps the ODD-indexed bytes: mac[1], mac[3], ... mac[15].
Uint8List truncateMac(Uint8List mac) {
  // TODO: 8 bytes, out[i] = mac[i * 2 + 1];
  throw UnimplementedError();
}

/// The 32-byte derivation vector, exactly as in Chapter 8.
Uint8List deriveSessionKey(
    Uint8List key, Uint8List rndA, Uint8List rndB, int label) {
  // TODO: sv[0] = label (0xA5 enc / 0x5A mac); sv[1] = the other;
  //       sv[2..5] = 00 01 00 80;
  //       sv[6..7] = rndA[0..1];
  //       sv[8..13] = rndA[2..7] ^ rndB[0..5];
  //       sv[14..23] = rndB[6..15];
  //       sv[24..31] = rndA[8..15];
  //       return aesCmac(key, sv);
  throw UnimplementedError();
}

/// ISO 7816-4: append 0x80 then zeros. ALWAYS adds at least one byte.
Uint8List padIso7816(Uint8List data) {
  final paddedLength = (data.length | 0x0F) + 1;
  final out = Uint8List(paddedLength);
  out.setRange(0, data.length, data);
  out[data.length] = 0x80;
  return out;
}
```

**Verify it with the same vectors.** This is the beautiful part: the test vectors from Appendix A are language-agnostic. Your Dart must produce byte-for-byte what your Java produced.

`test/desfire_crypto_test.dart`:

```dart
void main() {
  test('derives the Chapter 8 session keys', () {
    final key  = fromHex('00112233445566778899aabbccddeeff');
    final rndA = fromHex('a0a1a2a3a4a5a6a7a8a9aaabacadaeaf');
    final rndB = fromHex('b0b1b2b3b4b5b6b7b8b9babbbcbdbebf');

    expect(toHex(deriveSessionKey(key, rndA, rndB, 0xA5)),
           'd46c03528ecd3b1f1148e708d3e9729d');
    expect(toHex(deriveSessionKey(key, rndA, rndB, 0x5A)),
           '116d156cc502da820eb57915f189ec74');
  });

  test('truncates a CMAC to the odd bytes', () {
    expect(toHex(truncateMac(fromHex('00112233445566778899aabbccddeeff'))),
           '1133557799bbddff');
  });
}
```

```bash
flutter test           # no phone, no card -- same discipline as the Pi build
```

> If your Dart disagrees with your Java on any vector, **one of them is wrong** and you now know before either touches a card. This cross-language agreement is a genuinely strong check — two independent implementations converging on the same bytes is much better evidence than either alone.

### The card class — mostly a transcription

`lib/desfire/desfire_card.dart` mirrors your Java `DesfireCard`: `selectApplication`, `authenticateEv2First`, `getValue`, and the new one:

```dart
/// Credit (0x0C) in CommMode.Full -- the mirror image of Debit.
///
/// Identical wire format to debit; only the command byte differs. Like debit
/// it only STAGES the change: nothing moves until commitTransaction().
Future<void> credit(int fileNo, int amountCents) async {
  // TODO: same shape as Chapter 10's debit:
  //   header     = [fileNo]
  //   ciphertext = session.encryptCommandData(encodeValue(amountCents))
  //   macInput   = header + ciphertext
  //   mac        = session.commandMac(0x0C, macInput)
  //   send, check status, incrementCounter, verify response MAC over empty
  throw UnimplementedError();
}
```

**The top-up sequence, and why it reads back:**

```dart
Future<int> topUp(int fileNo, int amountCents) async {
  final before = await getValue(fileNo);
  await credit(fileNo, amountCents);
  await commitTransaction();
  final after = await getValue(fileNo);      // ← trust nothing; re-read

  if (after != before + amountCents) {
    throw StateError('balance did not move as expected');
  }
  return after;
}
```

Reading the balance back after the commit is not paranoia — it's the only way to *know*. If the phone loses the field between `credit` and `commitTransaction`, the card discards the delta and the balance is unchanged. Same safety property as Chapter 10, now protecting your customer's money instead of yours.

---

## Provisioning: turning a blank card into a wallet

A factory-fresh DESFire has no application, no value file, and a DES master key. To make one work with your kiosk:

```
1. authenticate at PICC level with the master key (16 × 0x00 factory default)
2. CreateApplication  (0xCA)  — AID 0x010203, 3 keys, AES
3. select the application, authenticate with its master key (initially 16 × 0x00)
4. ChangeKey          (0xC4)  — set app master key (0x11…) and user key (0x22…)
5. re-authenticate with the new app master key
6. CreateValueFile    (0xCC)  — file 0x01, CommMode.Full, access via key 2,
                                limits 0…100000, initial value 0
```

Wire format for `CreateValueFile`, 17 bytes:

```
[0]      fileNo
[1]      commSettings          0x03 = Full
[2..3]   accessRights          little-endian; nibbles: read|write|rw|changeRights
[4..7]   lowerLimit            signed LE
[8..11]  upperLimit            signed LE
[12..15] initialValue          signed LE
[16]     limitedCreditEnabled  0x00 / 0x01
```

The access-rights word packs four 4-bit key numbers: `read` (GetValue), `write` (Debit), `readWrite` (Credit too), `changeAccessRights`. Using key 2 for all four gives `0x2222`. Nibble `0xE` means free access, `0xF` means never.

> ⚠️ **`ChangeKey` is the dangerous one.** Set a key to a value you don't record and that card is scrap — permanently, by design. There is no recovery path; that's the security property. Before you run provisioning:
> - use **dedicated test cards** you bought for this project,
> - never leave the PICC master key changed from the factory default (this project deliberately doesn't touch it, so any other tool can re-personalise the card),
> - and never point this at a card you didn't buy for this — the DESFire in your wallet may be your office badge or transit pass, and it will not survive.

> ⚠️ **Never auto-provision on tap.** Require an explicit "Set up this card" button press. A member of the public tapping your app must never have their existing card silently rewritten.

---

## The security reality of hardcoded keys

Your app will contain, in plaintext, the AES keys for every card in the fleet. Anyone who pulls the APK off a phone can extract them and mint unlimited balance on every card you've issued.

**For a bench project with test cards this is fine.** Say so in a comment, out loud, so nobody mistakes it for a design:

```dart
/// ⚠️ BENCH KEYS. These are compiled into the APK in plaintext. Anyone who
/// pulls the APK can read them and mint unlimited balance on every card,
/// because the same key is on every card. Acceptable for a bench rig with
/// test cards. NOT acceptable for anything representing real money.
```

**What a real deployment does instead**, and it's worth understanding even if you never build it:

1. **Key diversification.** Each card gets a *different* key, derived from the card's UID and a master key (typically via CMAC). Compromise one card and you learn one card's key — not the fleet's.
2. **A backend HSM or SAM.** The master key never leaves tamper-resistant hardware. The phone becomes a dumb pipe: it relays APDUs between the card and the server, and the *server* performs the crypto.
3. **Server-side transaction logs**, so the balance on the card is a cache, not the source of truth — and double-spends are detectable after the fact.

The gap between "works" and "safe to deploy" is mostly key management, and it's why payment systems have HSMs. Knowing exactly where your bench rig sits on that spectrum is the professional part.

---

## Build order (do it in this order)

1. **`flutter create` + dependencies**, blank app runs on your phone.
2. **Crypto module + tests.** No NFC yet. Get all vectors green — same rule as the Pi build.
3. **`IsoDepTransceiver`** and a "read the card's version" screen. Prove `90 60 00 00 00` returns `…91AF`.
4. **`authenticateEv2First`.** Prove it on a card the kiosk already works with.
5. **`getValue`** — display the balance. Read-only, so mistakes are harmless.
6. **`credit` + `commit` + read-back.** Now money moves.
7. **Provisioning screen**, last, behind an explicit button.
8. **UI polish** — the NFC pulse animation, preset amounts, result screen.

Steps 2 and 5 are the natural stopping points if you're filming: "the crypto agrees across two languages" and "the phone reads the balance the Pi wrote."

---

## Traps

| Symptom | Cause |
|---|---|
| `nfc_manager` API doesn't match tutorials | 3.x vs 4.x are different. Pin 4.x and use its docs. |
| Works on one phone, not another | NFC antenna placement varies enormously. Try the upper third of the back. |
| Tag lost mid-transaction | Phone moved. This is *normal* — that's why commit exists. Handle it, don't fight it. |
| Dart and Java disagree on a MAC | Almost always the counter or TI, not the AES. Print both inputs. |
| `91 AE` after provisioning | You changed a key and re-authenticated with the old one. |
| Card bricked | You changed a key you didn't record. There is no recovery. This is why you buy spares. |

---

## Commit

```bash
git add -A
git commit -m "topup: Flutter app -- crypto ported, credit + commit, provisioning"
```

---

*Next: [13 — Deploying to the Pi](13-deploy.md)*
