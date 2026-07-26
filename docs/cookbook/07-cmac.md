# 07 — AES-CMAC from scratch

**Goal:** build the message authentication code DESFire EV2 runs on, and prove it against the RFC 4493 vectors.

**OOP concept:** immutable objects and constructor invariants.

---

## Read this before you write any crypto

Everything from here on is **verified against known-answer test vectors before it touches hardware.** This is not optional and it is not caution for its own sake.

Cryptographic code has a property that no other code you've written has: **wrong output is indistinguishable from right output.** A broken CRC gives you a mismatch you can see. A broken CMAC gives you sixteen bytes of perfect-looking randomness — exactly like a correct CMAC. You cannot eyeball it, you cannot reason about it from a failed transaction, and if you build three more layers on top before testing, you will have no idea which layer is wrong.

Worse, on real hardware a bad MAC doesn't just fail — the card may increment an internal error counter, and enough consecutive failures can permanently lock a key.

So: **vectors first, silicon second.** Every chapter in Part 3 ends with a test you must see green.

---

## What a MAC is

A **Message Authentication Code** answers: *"was this message written by someone holding the secret key, and has it been altered?"*

It's a keyed checksum. A CRC tells you a message survived the wire; a MAC tells you it survived an *adversary*. Without the key you cannot compute a valid MAC, so you cannot forge or tamper.

> **Analogy.** A wax seal on a letter. Anyone can read the letter, but only the person with the signet ring can produce the seal, and breaking it to alter the contents is obvious. The ring is the key; the seal is the MAC. Crucially the seal doesn't hide the letter — MACs authenticate, they don't encrypt. Those are separate jobs, and DESFire uses both.

**CMAC** (Cipher-based MAC, RFC 4493 / NIST SP 800-38B) builds a MAC out of a block cipher you already have. Roughly: it's CBC-mode encryption where you throw away everything except the last block. That final block depends on every bit of the message and on the key, so it works as a fingerprint.

The subtlety CMAC adds — and the reason it's not just "CBC-MAC" — is how it handles the **last block**, which is where naive CBC-MAC has a forgery weakness. CMAC derives two subkeys, `K1` and `K2`, and XORs one of them into the final block depending on whether the message needed padding. That's the whole trick.

---

## The algorithm

### Part 1 — subkey generation (RFC 4493 §2.3)

```
L  = AES-ECB(K, 0^128)                    encrypt an all-zero block
K1 = L << 1;   if MSB(L)  was set:  K1 ^= Rb
K2 = K1 << 1;  if MSB(K1) was set:  K2 ^= Rb
```

- `Rb = 0x87`, the constant from AES's finite-field polynomial. "`^= Rb`" means XOR **the last byte** with `0x87`.
- `MSB set` means the top bit of the *first* byte: `(x[0] & 0x80) != 0`.
- `<< 1` shifts the whole 128-bit block left one bit — the bit that falls off byte *i+1*'s top becomes byte *i*'s bottom.

That last one is the only fiddly part. In C you'd do it identically:

```java
byte[] out = new byte[16];
int carry = 0;
for (int i = 15; i >= 0; i--) {      // walk BACKWARDS, low byte first
    int b = in[i] & 0xFF;
    out[i] = (byte) ((b << 1) | carry);
    carry = (b >> 7) & 1;            // the bit shifted out of this byte
}
```

You walk from the last byte to the first because a carry propagates *upward* toward the more significant end.

### Part 2 — the MAC (RFC 4493 §2.4)

```
split the message into 16-byte blocks M_1 … M_n
the LAST block is special:
    if it is exactly 16 bytes:   M_last = M_n XOR K1
    otherwise (incl. empty):     pad it (append 0x80 then 0x00s to 16)
                                 M_last = padded XOR K2
then CBC-MAC:
    X = 0^128
    for each block except the last:   X = AES(K, X XOR M_i)
    T = AES(K, X XOR M_last)          ← the CMAC
```

The `K1` vs `K2` choice is what makes "a 16-byte message" and "a 15-byte message that got padded to 16" produce different MACs. Without it, an attacker could exploit the ambiguity.

---

## Build it

First, thin AES helpers. This is the one piece I hand you complete — reimplementing AES itself teaches you nothing here, and the JDK's is correct and fast.

`src/main/java/com/midnightbrewer/hardware/crypto/Aes.java`:

```java
package com.midnightbrewer.hardware.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Thin AES-128 helpers over the JDK's crypto provider.
 *
 * NoPadding everywhere: DESFire does its own ISO 7816-4 padding, and letting
 * the JDK add PKCS#5 on top would corrupt every frame.
 */
public final class Aes {

    public static final int BLOCK = 16;

    private Aes() { }

    public static byte[] ecbEncrypt(byte[] key, byte[] block) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(block);
        } catch (Exception e) {
            throw new IllegalStateException("AES-ECB failed", e);
        }
    }

    public static byte[] cbcEncrypt(byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                   new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-CBC encrypt failed", e);
        }
    }

    public static byte[] cbcDecrypt(byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                   new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES-CBC decrypt failed", e);
        }
    }

    public static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }
}
```

Now the part you write. `src/main/java/com/midnightbrewer/hardware/crypto/AesCmac.java`:

```java
package com.midnightbrewer.hardware.crypto;

/**
 * AES-CMAC (RFC 4493) -- the MAC DESFire EV2 uses. The JDK has no CMAC, so
 * this is the from-scratch part; you are not reimplementing AES, you are
 * building the CMAC construction on top of Aes.ecbEncrypt.
 *
 * OOP on show: the key and the two derived subkeys are computed ONCE in the
 * constructor and kept private. compute() never mutates them. That makes this
 * an immutable object with an invariant established at birth: "if this object
 * exists, its subkeys are correct and match its key."
 */
public final class AesCmac {

    private static final int BLOCK = 16;
    private static final byte RB = (byte) 0x87;  // from the AES field polynomial

    private final byte[] key;
    private final byte[] k1;
    private final byte[] k2;

    public AesCmac(byte[] key) {
        if (key.length != BLOCK) {
            throw new IllegalArgumentException("AES-128 key must be 16 bytes");
        }
        this.key = key.clone();
        byte[][] subkeys = generateSubkeys(key);
        this.k1 = subkeys[0];
        this.k2 = subkeys[1];
    }

    // ─── RFC 4493 §2.3 ───────────────────────────────────────────────
    private static byte[][] generateSubkeys(byte[] key) {
        // TODO 1: byte[] l = Aes.ecbEncrypt(key, new byte[BLOCK]);   // L = AES(K, 0^128)
        // TODO 2: byte[] k1 = leftShift(l);
        //         if ((l[0] & 0x80) != 0) k1[15] ^= RB;
        // TODO 3: byte[] k2 = leftShift(k1);
        //         if ((k1[0] & 0x80) != 0) k2[15] ^= RB;
        // TODO 4: return new byte[][]{ k1, k2 };
        return new byte[][]{ new byte[BLOCK], new byte[BLOCK] };
    }

    /** Shift a 16-byte block left by ONE bit, as a single 128-bit number. */
    private static byte[] leftShift(byte[] in) {
        // TODO: walk from the LAST byte to the first, carrying the top bit
        //       of each byte down into the next one. (See the chapter text.)
        return new byte[BLOCK];
    }

    // ─── RFC 4493 §2.4 ───────────────────────────────────────────────
    public byte[] compute(byte[] message) {
        // TODO 1: how many blocks, and is the last one full?
        //   if (message.length == 0) { n = 1; lastComplete = false; }
        //   else { n = ceil(message.length / 16); lastComplete = (len % 16 == 0); }

        // TODO 2: build M_last.
        //   if lastComplete: mLast = Aes.xor(lastBlock, k1)
        //   else: pad into a 16-byte buffer -- copy the leftover bytes,
        //         set padded[leftoverLen] = (byte) 0x80, rest stays zero;
        //         mLast = Aes.xor(padded, k2)

        // TODO 3: CBC-MAC through the blocks:
        //   byte[] x = new byte[BLOCK];
        //   for (int i = 0; i < n - 1; i++)
        //       x = Aes.ecbEncrypt(key, Aes.xor(x, i-th 16-byte slice));
        //   return Aes.ecbEncrypt(key, Aes.xor(x, mLast));
        return new byte[BLOCK];
    }
}
```

> **Why compute the subkeys in the constructor?** Because they are a pure function of the key, and computing them per-`compute()` call would waste an AES operation on every MAC — of which a transaction does many.
>
> But the deeper reason is **the invariant**. The constructor is the one place that can guarantee "this object is valid." It validates the key length and derives the subkeys; after that, the object cannot be in a bad state, because nothing can change it. Contrast a design with a `setKey()` method: now every method must ask "has the key been set? are the subkeys stale?" — questions that don't exist if you make them impossible.
>
> **Establish invariants in the constructor; make fields `final`; expose no setters.** Half of "good OOP" is that one habit.

---

## Verify — RFC 4493 vectors

These are from the RFC itself: independent of you, independent of me, and definitive.

**Key `K = 2b7e151628aed2a6abf7158809cf4f3c`** (the standard AES test key)

| Message | Expected CMAC |
|---|---|
| *(empty)* | `bb1d6929e95937287fa37d129b756746` |
| `6bc1bee22e409f96e93d7e117393172a` | `070a16b46b4d4144f79bdd9dd04a287c` |
| `6bc1bee22e409f96e93d7e117393172a`<br>`ae2d8a571e03ac9c9eb76fac45af8e51`<br>`30c81c46a35ce411` *(40 bytes)* | `dfa66747de9ae63030ca32611497c827` |
| the above + `e5fbc1191a0a52ef`<br>`f69f2445df4f9b17ad2b417be66c3710` *(64 bytes)* | `51f0bebf7e3b9d92fc49741779363cfe` |

These four are chosen to hit every branch: empty (pad + K2), exactly one block (K1), a partial last block (pad + K2), and multiple full blocks (K1). If all four pass, your implementation is right.

The subkeys, if you want to check them mid-debug:
```
K1 = fbeed618357133667c85e08f7236a8de
K2 = f7ddac306ae266ccf90bc11ee46d513b
```

`src/test/java/com/midnightbrewer/hardware/crypto/AesCmacTest.java`:

```java
package com.midnightbrewer.hardware.crypto;

import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AesCmacTest {

    private static final byte[] K =
            HexFormat.of().parseHex("2b7e151628aed2a6abf7158809cf4f3c");

    private static String mac(String messageHex) {
        byte[] m = HexFormat.of().parseHex(messageHex);
        return HexFormat.of().formatHex(new AesCmac(K).compute(m));
    }

    @Test
    void rfc4493_example1_empty() {
        assertEquals("bb1d6929e95937287fa37d129b756746", mac(""));
    }

    @Test
    void rfc4493_example2_oneBlock() {
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c",
                mac("6bc1bee22e409f96e93d7e117393172a"));
    }

    @Test
    void rfc4493_example3_partialLastBlock() {
        assertEquals("dfa66747de9ae63030ca32611497c827",
                mac("6bc1bee22e409f96e93d7e117393172a"
                  + "ae2d8a571e03ac9c9eb76fac45af8e51"
                  + "30c81c46a35ce411"));
    }

    @Test
    void rfc4493_example4_fullBlocks() {
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe",
                mac("6bc1bee22e409f96e93d7e117393172a"
                  + "ae2d8a571e03ac9c9eb76fac45af8e51"
                  + "30c81c46a35ce411e5fbc1191a0a52ef"
                  + "f69f2445df4f9b17ad2b417be66c3710"));
    }
}
```

```bash
mvn test -Dtest=AesCmacTest      # all four must be green
```

`HexFormat` is a Java 17+ built-in — no dependency needed for hex parsing and printing, which you'll do constantly from here on.

---

## Traps

| Symptom | Cause |
|---|---|
| Empty-message vector fails, others pass | The `n = 1, lastComplete = false` special case for an empty message. |
| Partial-block vectors fail | Padding wrong: it's `0x80` then zeros, not all zeros. |
| Full-block vectors fail | Using K2 where K1 belongs (or vice versa). |
| Everything fails, all plausible-looking | `leftShift` carry direction — you must walk backwards. |
| `k1[15] ^= RB` has no effect | You XOR'd a copy. Java arrays are references; `k1[15] ^= RB` mutates in place, which is what you want here. |
| Sporadic wrong results | You mutated `this.key` or a subkey somewhere. They must be write-once. |

---

## Commit

```bash
git add -A
git commit -m "M7a: AES-CMAC (RFC 4493), verified against the RFC vectors"
```

---

*Next: [08 — Session keys](08-session-keys.md)*
