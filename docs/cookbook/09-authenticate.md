# 09 — Mutual authentication

**Goal:** prove to the card you hold its key, make it prove the same to you, and establish a session — without either side ever transmitting the key.

**OOP concept:** injecting randomness as a seam.

---

## The problem

You and the card both (allegedly) hold the same 16-byte AES key. How do you each verify that about the other, over a radio link anyone can listen to, without sending the key?

You can't send it. You can't send a hash of it (an eavesdropper replays the hash). You need each side to demonstrate *use* of the key on something new every time.

## The idea: challenge–response, in both directions

I send you a random number and ask you to encrypt it. Only someone with the key can produce the right answer. Because the number is new each time, a recorded answer is worthless later.

Do it in both directions and you have **mutual** authentication: the reader proves itself to the card *and* the card proves itself to the reader. That second half matters more than people assume — it's what stops a fake card, and it's why the same nonces can safely seed the session keys.

> **Analogy.** Two spies meeting. One says "the nightingale sings at midnight" — a line only someone briefed would recognise. If only *one* side gives a passphrase, an impostor can collect it and use it elsewhere. Both sides challenging each other means neither can be impersonated.
>
> The rotation (below) is the detail that makes it work: you don't send back *the number you were given*, you send back a **transformation** of it, proving you actually decrypted and processed it rather than echoing bytes.

---

## The dance

```
   READER                                                    CARD
     │                                                         │
     │  90 71 00 00 02 [keyNo] [00] 00                          │
     ├────────────────────────────────────────────────────────►│
     │                                        generates RndB (16 random bytes)
     │                    E(K, RndB)  +  91 AF                  │
     │◄────────────────────────────────────────────────────────┤
     │                                                         │
  decrypt → RndB                                               │
  rotate left 1 → RndB'                                        │
  generate RndA (16 random bytes)                              │
  encrypt (RndA ‖ RndB') as ONE 32-byte CBC run                │
     │                                                         │
     │  90 AF 00 00 20 [E(K, RndA‖RndB')] 00                    │
     ├────────────────────────────────────────────────────────►│
     │                             decrypts, checks RndB' matches what it sent
     │                             → the READER is authentic
     │        E(K, TI ‖ RndA' ‖ caps)  +  91 00                 │
     │◄────────────────────────────────────────────────────────┤
     │                                                         │
  decrypt, check RndA' == rotateLeft(RndA)                     │
  → the CARD is authentic                                      │
  keep TI; derive Kenc/Kmac from RndA + RndB                   │
```

**Every AES operation here uses an all-zero IV.** Every single one. That's unusual (normally a fixed IV is a flaw) and it's safe *only* because every plaintext contains fresh randomness. Don't carry the habit anywhere else.

**Rotate left by one byte** means `b[1], b[2], … b[15], b[0]` — a byte rotation, not a bit rotation:

```
rotateLeft(b0b1b2b3b4b5b6b7b8b9babbbcbdbebf)
         = b1b2b3b4b5b6b7b8b9babbbcbdbebfb0
```

**`TI`** (Transaction Identifier) is 4 bytes the card generates fresh for this session. From Chapter 10 on, it goes into every MAC and every IV, binding all subsequent traffic to *this* authentication. It's the session's fingerprint.

### The step-2 reply, byte by byte

The card returns 32 encrypted bytes; decrypted they are:

```
[0..4)    TI            4 bytes  — the transaction identifier
[4..20)   RndA'        16 bytes  — your RndA, rotated left
[20..26)  PDcap2        6 bytes  — card capabilities
[26..32)  PCDcap2       6 bytes  — reader capabilities echoed back
```

You need bytes `[0..4)` and `[4..20)`. The capabilities you can ignore.

---

## The testability problem, and the seam that solves it

Here's a genuine engineering puzzle. This function calls a random number generator. That means **it produces different output every run** — so how do you write a known-answer test for it?

The answer is the same move as Chapter 1: **the thing that varies becomes a parameter.** Randomness is a dependency like any other. Inject it, and a test can inject a *fixed* "random" number, making the whole handshake deterministic and checkable against vectors.

`src/main/java/com/midnightbrewer/hardware/RandomSource.java`:

```java
package com.midnightbrewer.hardware;

import java.security.SecureRandom;

/**
 * A source of random bytes -- the seam that makes the auth handshake testable.
 *
 * Production uses secure(). A test injects a fake returning a FIXED RndA, and
 * suddenly the whole handshake is a known-answer test.
 *
 * @FunctionalInterface = exactly one abstract method, so a fake can be written
 * as a one-line lambda:   RandomSource fixed = n -> myFixedBytes;
 */
@FunctionalInterface
public interface RandomSource {

    byte[] nextBytes(int n);

    /** The real one, backed by a cryptographically secure RNG. */
    static RandomSource secure() {
        SecureRandom rng = new SecureRandom();
        return n -> {
            byte[] out = new byte[n];
            rng.nextBytes(out);
            return out;
        };
    }
}
```

> ⚠️ **`SecureRandom`, never `java.util.Random`.** `Random` is a linear congruential generator: observe a couple of outputs and you can predict every future one. For a nonce that is the entire security of the protocol. `SecureRandom` draws from the OS entropy pool. This is a one-word difference with a catastrophic failure mode, and it's exactly the kind of thing that ships.

> **A caution about the seam itself:** it exists so tests can pin randomness. If production code ever passes anything but `RandomSource.secure()`, the protocol is broken. Test doubles live in `src/test`, and that's not an accident.

---

## Build it

`src/main/java/com/midnightbrewer/hardware/DesfireCard.java`:

```java
package com.midnightbrewer.hardware;

import com.midnightbrewer.hardware.crypto.Aes;
import com.midnightbrewer.hardware.crypto.DesfireSession;
import java.util.Arrays;

/**
 * A DESFire card, spoken to over an ApduChannel.
 *
 * COMPOSITION: it HAS-A channel and HAS-A random source, both injected. It
 * knows nothing about SPI, the RC522, or ISO-DEP framing -- only APDUs in and
 * responses out. That is why it can be tested with no hardware at all.
 */
public class DesfireCard {

    private final ApduChannel channel;
    private final RandomSource randomSource;

    private byte[] ti;                 // transaction identifier, set at auth
    private DesfireSession session;    // the derived keys, set at auth

    public DesfireCard(ApduChannel channel, RandomSource randomSource) {
        this.channel = channel;
        this.randomSource = randomSource;
    }

    /**
     * Wrap a DESFire command in an ISO-7816 APDU and send it.
     *   90 [cmd] 00 00 [Lc] [data...] 00
     */
    private byte[] wrapApdu(byte cmd, byte[] data) throws SpiException {
        byte[] apdu = new byte[data.length + 6];
        apdu[0] = (byte) 0x90;              // CLA: DESFire native, wrapped
        apdu[1] = cmd;                      // INS
        apdu[2] = 0x00;                     // P1
        apdu[3] = 0x00;                     // P2
        apdu[4] = (byte) data.length;       // Lc
        System.arraycopy(data, 0, apdu, 5, data.length);
        apdu[apdu.length - 1] = 0x00;       // Le
        return channel.transceive(apdu);
    }

    /** Rotate left by one BYTE: b[1..n-1] followed by b[0]. */
    private static byte[] rotateLeft(byte[] array) {
        // TODO
        return array;
    }

    /** SelectApplication (0x5A). The AID goes on the wire LSB-first. */
    public void selectApplication(byte[] aid) throws SpiException {
        if (aid.length != 3) {
            throw new IllegalArgumentException("AID must be 3 bytes");
        }
        byte[] response = wrapApdu((byte) 0x5A, aid);
        if (response.length < 2) {
            throw new SpiException("SelectApplication failed");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // AuthenticateEV2First (0x71). All AES here uses an ALL-ZERO IV.
    // ═════════════════════════════════════════════════════════════════
    public void authenticateEv2First(byte keyNo, byte[] key) throws SpiException {
        if (key.length != 16) {
            throw new IllegalArgumentException("key must be 16 bytes");
        }

        // ── step 1: ask for the challenge ────────────────────────────
        // TODO 1: byte[] r1 = wrapApdu((byte) 0x71, new byte[]{ keyNo, 0x00 });
        //   ⚠️ TWO data bytes: the key number AND a 0x00 "length of
        //   capabilities" byte. Sending only keyNo gets you 91 7E (length
        //   error) and it is a genuinely annoying hour to find.
        // TODO 2: strip the 2 status bytes -> encRndB (16 bytes)
        // TODO 3: byte[] rndB = Aes.cbcDecrypt(key, new byte[16], encRndB);

        // ── step 2: answer, and challenge back ───────────────────────
        // TODO 4: byte[] rndBRot = rotateLeft(rndB);
        //         byte[] rndA    = randomSource.nextBytes(16);
        // TODO 5: build a 32-byte buffer: rndA then rndBRot.
        //         byte[] encAB = Aes.cbcEncrypt(key, new byte[16], that);
        //   Note: ONE 32-byte CBC run, not two 16-byte ones. In CBC the second
        //   block is chained off the first's ciphertext -- encrypting them
        //   separately gives a different, wrong answer.
        // TODO 6: byte[] r2 = wrapApdu((byte) 0xAF, encAB);
        //         strip the status bytes -> encResp (32 bytes)

        // ── step 3: verify the card, keep the session ────────────────
        // TODO 7: byte[] plain = Aes.cbcDecrypt(key, new byte[16], encResp);
        // TODO 8: byte[] rndARot = Arrays.copyOfRange(plain, 4, 20);
        //         if (!Arrays.equals(rndARot, rotateLeft(rndA)))
        //             throw new SpiException("authentication failed: RndA mismatch");
        //   ⚠️ THIS CHECK IS THE WHOLE POINT. Skip it and you have not
        //   authenticated the card -- you have merely talked to something.
        // TODO 9: this.ti = Arrays.copyOfRange(plain, 0, 4);
        //         this.session = new DesfireSession(key, rndA, rndB);
    }

    public byte[] getTi() {
        return ti;
    }
}
```

---

## Verify — the full handshake, offline

The elegant part: your test class can **be** the fake card and the fake RNG at once.

```java
package com.midnightbrewer.hardware;

import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The test IS its own fake card + fake RNG: it implements both seams, so
 * new DesfireCard(this, this) plugs the test straight into the card.
 */
public class DesfireCardTest implements RandomSource, ApduChannel {

    byte[] key   = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
    byte[] rndA  = HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
    byte[] encAB = HexFormat.of().parseHex(
            "cf086a82c0b745a749daabb28a7a8db332272f9e5fdc37a4746064f25b7c2ca7");
    byte[] tiExpected = HexFormat.of().parseHex("11223344");

    // ⚠️ Replies MUST carry the trailing status word -- the card code strips
    // the last two bytes off every reply. Without them you chop real
    // ciphertext and decrypt garbage.
    byte[] selectReply = HexFormat.of().parseHex("9100");
    byte[] step1Reply  = HexFormat.of().parseHex(
            "eab6822fe368d5bc9895fb2558b38dde" + "91af");
    byte[] step2Reply  = HexFormat.of().parseHex(
            "4e08e447d74810052caa1c5b6c60343f"
          + "51f6e901ad171e677b100d3a34c925e4" + "9100");

    @Override
    public byte[] nextBytes(int n) {
        return rndA;                        // pin RndA -> deterministic
    }

    @Override
    public byte[] transceive(byte[] apdu) throws SpiException {
        switch (apdu[1]) {                  // dispatch on INS
            case (byte) 0x5A:
                return selectReply;
            case (byte) 0x71:
                return step1Reply;
            case (byte) 0xAF:
                // assert on what we SENT: encAB sits at [5 .. len-1)
                assertArrayEquals(encAB,
                        Arrays.copyOfRange(apdu, 5, apdu.length - 1),
                        "step-2 ciphertext is wrong");
                return step2Reply;
            default:
                throw new SpiException("unexpected APDU: " + Arrays.toString(apdu));
        }
    }

    @Test
    public void authenticateEv2First_matchesReplayVectors() throws SpiException {
        DesfireCard card = new DesfireCard(this, this);

        card.selectApplication(new byte[]{ 0x01, 0x02, 0x03 });
        card.authenticateEv2First((byte) 0x00, key);   // no throw == RndA' verified

        assertArrayEquals(tiExpected, card.getTi());
    }
}
```

**Why this test is strong.** It checks both directions:
- the `0xAF` case asserts the exact ciphertext **you sent** — proving your rotate, your concatenation and your CBC encryption are all correct;
- reaching the end without throwing proves your `RndA'` verification passed;
- the `TI` assertion proves you sliced the reply correctly.

```bash
mvn test -Dtest=DesfireCardTest
```

The full vector set is in [Appendix A](A-test-vectors.md).

---

## Then: on the real card

Wire it into `HardwareCheck` after activation, using your card's actual personalisation (see [Appendix A](A-test-vectors.md) for where those values come from):

```java
DesfireCard desfire = new DesfireCard(channel, RandomSource.secure());
desfire.selectApplication(new byte[]{ 0x03, 0x02, 0x01 });   // AID 0x010203, LSB-first
byte[] appKey = new byte[16];
Arrays.fill(appKey, (byte) 0x22);                            // your app's user key
desfire.authenticateEv2First((byte) 0x02, appKey);           // key slot 2
System.out.println("MUTUAL AUTH OK. TI = " + hex(desfire.getTi()));
```

**Success looks like:**

```
MUTUAL AUTH OK. TI = 8071F582
```

That `TI` will be **different every run** — it's freshly generated per session. Seeing it change is your proof that this was a live authentication and not a replay of your test vectors.

> ⚠️ A factory-blank DESFire will fail here: its default master key is DES, not AES, and there's no application yet. The card needs to be personalised first — that's Chapter 12's job. If you don't have a provisioned card yet, use the Flutter/Android app's setup screen, or borrow the reference implementation's wallet demo.

---

## Traps

| Symptom | Cause |
|---|---|
| `91 7E` (length error) on step 1 | Sent only `keyNo`. It must be `{ keyNo, 0x00 }`. |
| `91 AE` (auth error) on step 2 | Wrong key, wrong key number, or wrong application selected. |
| RndA mismatch, vectors pass | Real card is fine — check you rotated *left*, and by a byte not a bit. |
| Test decrypts garbage | Fake replies missing their `91 xx` status word. |
| Encryption differs from the vector | You encrypted RndA and RndB' as two separate CBC calls. It's one 32-byte run. |
| Everything works but auth "succeeds" always | You skipped the `RndA'` comparison. Verify you actually throw on mismatch. |

---

## Commit

```bash
git add -A
git commit -m "M7c: AuthenticateEV2First, verified offline against replay vectors"
```

---

*Next: [10 — Secure messaging & the debit](10-secure-messaging.md) — where money finally moves.*
