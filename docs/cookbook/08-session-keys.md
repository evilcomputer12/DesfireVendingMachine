# 08 — Session keys

**Goal:** derive the two per-transaction AES keys from the two random nonces exchanged during authentication.

**OOP concept:** private static helpers, enums instead of boolean flags.

---

## The problem: never use the long-term key directly

Your card holds a permanent AES-128 key. It would be technically possible to encrypt every message with it directly. It would also be a bad idea, for reasons worth understanding:

- **Replay.** If the same key encrypts everything forever, a recorded "debit 1.50" frame stays valid. An attacker records one purchase and replays it — or replays a *credit*.
- **Traffic analysis.** The same plaintext under the same key gives the same ciphertext, every time. An eavesdropper learns which commands are which without breaking anything.
- **Exposure.** Every message encrypted with the long-term key is another sample for cryptanalysis. Keys wear out with use.

So DESFire does what TLS does: use the long-term key **once**, to bootstrap a pair of throwaway keys valid only for this tap.

> **Analogy.** A hotel doesn't hand guests the master key that opens every door forever. You present ID at the desk once (the long-term secret), and they program a card that opens *your* room, *this week*. Lose it and the damage is bounded; the master key never left the safe.
>
> The nonces are what make each session unique. Both sides contribute randomness, so neither can force a repeat — that matters, because if the card alone chose, a compromised card could replay the same session forever.

**Two keys, not one**, because encryption and authentication are different jobs and reusing one key for both is a classic footgun:

- **`Kenc`** — encrypts data (confidentiality: nobody can *read* the balance)
- **`Kmac`** — authenticates commands (integrity: nobody can *alter* the debit)

---

## The derivation

Both keys come from the same construction with a different label byte:

```
SV[0]      label            0xA5 for Kenc, 0x5A for Kmac
SV[1]      counter-label    the other one (0x5A when label is 0xA5)
SV[2..5]   00 01 00 80      fixed
SV[6..7]   rndA[0..1]
SV[8..13]  rndA[2..7] XOR rndB[0..5]      ← the interleave
SV[14..23] rndB[6..15]
SV[24..31] rndA[8..15]

session key = CMAC(authKey, SV)          32 bytes in, 16 out
```

The CMAC you built in Chapter 7 is doing duty here as a **key derivation function**: it mixes a lot of input down into 16 bytes in a way that's impossible to invert. That's the same property that makes it a good MAC.

**Why the strange interleave?** Both sides must derive identical keys, so the layout is just a specification both implement. But note *why it's mixed rather than concatenated*: bytes from both nonces are XOR'd together, so neither party can steer the result by choosing their nonce cleverly. If the layout were `rndA || rndB`, a malicious reader could pick `rndA` to force structure into the derived key.

**Why `0xA5`/`0x5A`?** They're bit-complements of each other, chosen to be maximally distinct — so the enc and MAC keys can't accidentally collide. And the label sits at byte 0, meaning the very first AES block of the CMAC already differs, so the two derivations diverge immediately.

---

## Build it

`src/main/java/com/midnightbrewer/hardware/crypto/DesfireSession.java`:

```java
package com.midnightbrewer.hardware.crypto;

import java.util.Arrays;

/**
 * One authenticated session with a card: the two derived keys, and (from
 * Chapter 10) the secure-messaging state that goes with them.
 *
 * The keys are derived ONCE in the constructor and never change -- same
 * invariant discipline as AesCmac. If a DesfireSession exists, its keys are
 * valid and belong together.
 */
public class DesfireSession {

    private final byte[] keyEnc;
    private final byte[] keyMac;

    /**
     * Which key we're deriving.
     *
     * An ENUM, not a boolean. deriveSessionKey(key, a, b, true) at a call site
     * tells you nothing -- true what? SessionKeyType.ENC is self-documenting,
     * the compiler rejects anything else, and adding a third key type later
     * does not change any existing signature.
     */
    private enum SessionKeyType { ENC, MAC }

    public DesfireSession(byte[] authKey, byte[] rndA, byte[] rndB) {
        if (authKey.length != 16) {
            throw new IllegalArgumentException("auth key must be 16 bytes");
        }
        this.keyEnc = deriveSessionKey(authKey, rndA, rndB, SessionKeyType.ENC);
        this.keyMac = deriveSessionKey(authKey, rndA, rndB, SessionKeyType.MAC);
    }

    // ═════════════════════════════════════════════════════════════════
    // private static: it is a pure function (same inputs -> same output,
    // touches no instance state), so it does not need 'this'. Marking it
    // static says exactly that, and lets the constructor call it before the
    // object is finished being built.
    // ═════════════════════════════════════════════════════════════════
    private static byte[] deriveSessionKey(byte[] authKey, byte[] rndA,
                                           byte[] rndB, SessionKeyType type) {
        if (rndA.length != 16 || rndB.length != 16) {
            throw new IllegalArgumentException("nonces must be 16 bytes");
        }
        byte[] sv = new byte[32];

        // TODO 1: sv[0] = 0xA5 for ENC, 0x5A for MAC.
        //         sv[1] = the opposite one.
        // TODO 2: sv[2..5] = 0x00, 0x01, 0x00, 0x80
        // TODO 3: sv[6] = rndA[0];  sv[7] = rndA[1];
        // TODO 4: sv[8..13] = rndA[2..7] XOR rndB[0..5]
        //         (Arrays.copyOfRange + Aes.xor is the tidy way)
        // TODO 5: sv[14..23] = rndB[6..15]     (10 bytes)
        // TODO 6: sv[24..31] = rndA[8..15]     (8 bytes)
        // TODO 7: return new AesCmac(authKey).compute(sv);
        return new byte[16];
    }

    /** Defensive copies -- callers must not be able to scribble on our keys. */
    public byte[] encKey() {
        return keyEnc.clone();
    }

    public byte[] macKey() {
        return keyMac.clone();
    }
}
```

### Two design notes worth absorbing

**1. Enum over boolean.** Compare these call sites:

```java
deriveSessionKey(key, rndA, rndB, true);                    // true... what?
deriveSessionKey(key, rndA, rndB, SessionKeyType.ENC);      // obvious
```

A boolean parameter is a note-to-self that the reader doesn't get to see. This is sometimes called the "boolean trap." Any time you're about to add a `boolean` parameter that selects *behaviour* rather than expressing *a yes/no fact*, an enum is better: self-documenting at the call site, extensible without changing signatures, and impossible to pass wrong.

**2. Don't keep scratch work as state.** A tempting first draft stores `sv` and the derived key as fields:

```java
private byte[] SV;      // ← don't
private byte[] SK;      // ← don't
```

Now the object has fields that are only meaningful *during* construction. Anyone reading the class has to work out whether they're still valid later. Worse, a second call to a derive method would silently clobber them.

**Scratch work belongs in local variables.** Fields are for state the object genuinely *has*. `keyEnc` and `keyMac` are state; `sv` is a temporary. The test: *"if someone asked this object about this field a minute from now, would the answer be meaningful?"*

---

## Verify

These vectors were generated independently (Python `cryptography`), so agreement means your Java is right — not merely self-consistent.

```
authKey = 00112233445566778899aabbccddeeff
rndA    = a0a1a2a3a4a5a6a7a8a9aaabacadaeaf
rndB    = b0b1b2b3b4b5b6b7b8b9babbbcbdbebf

→ Kenc  = d46c03528ecd3b1f1148e708d3e9729d
→ Kmac  = 116d156cc502da820eb57915f189ec74
```

```java
package com.midnightbrewer.hardware.crypto;

import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DesfireSessionTest {

    @Test
    void derivesBothSessionKeys() {
        byte[] authKey = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        byte[] rndA    = HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
        byte[] rndB    = HexFormat.of().parseHex("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf");

        DesfireSession session = new DesfireSession(authKey, rndA, rndB);

        assertEquals("d46c03528ecd3b1f1148e708d3e9729d",
                HexFormat.of().formatHex(session.encKey()));
        assertEquals("116d156cc502da820eb57915f189ec74",
                HexFormat.of().formatHex(session.macKey()));
    }
}
```

```bash
mvn test -Dtest=DesfireSessionTest
```

**If only one of the two keys is wrong,** your `sv[0]`/`sv[1]` labels are swapped or the counter-label is wrong. **If both are wrong,** the SV layout is off — print your `sv` as hex and compare byte-by-byte against the table above. For these inputs it should be:

```
a55a00010080 a0a1 10101010101 0 …
```

more precisely: `a5` `5a` `00 01 00 80` `a0 a1` then `rndA[2..7]^rndB[0..5]`, and so on. Printing the SV and eyeballing the first eight bytes finds most mistakes in seconds.

---

## Traps

| Symptom | Cause |
|---|---|
| Both keys wrong | SV layout. Print it and compare against the spec table. |
| Keys swapped | Label bytes `0xA5`/`0x5A` reversed. |
| `ArrayIndexOutOfBounds` in the XOR | Slice lengths: `rndA[2..7]` is 6 bytes, `rndB[6..15]` is 10, `rndA[8..15]` is 8. They must total 32 with the header. |
| Works in the test, fails on card | You passed `rndB` where `rndA` belongs (Chapter 9) — the order matters. |

---

## Commit

```bash
git add -A
git commit -m "M7b: EV2 session key derivation, verified against independent vectors"
```

---

*Next: [09 — Mutual authentication](09-authenticate.md)*
