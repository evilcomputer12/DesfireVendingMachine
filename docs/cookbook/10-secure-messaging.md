# 10 — Secure messaging & the debit

**Goal:** read the balance and spend money. This is the chapter where the project becomes real.

**OOP concept:** growing a class as its responsibilities grow.

---

## The problem

You're authenticated. But the card will no longer accept a bare command — every one must now prove three things:

1. **Authenticity** — an 8-byte CMAC computed with `Kmac`.
2. **Freshness** — a command counter, so no frame can be replayed.
3. **Confidentiality** (for values) — the amount encrypted with `Kenc`.

That's "CommMode.Full", and it's what a value file configured for full protection demands.

> **Analogy.** A cheque. The **amount** is written in the box (the encrypted data). The **signature** proves you authorised it (the MAC). And the **cheque number** stops the payee photocopying it and cashing it twice (the counter). Remove any one and the system fails: no signature, anyone writes cheques; no number, one cheque pays forever; no amount protection, someone alters 10 to 1000.

---

## The command counter, and the ordering that will bite you

`CmdCtr` is a 16-bit counter that starts at **0** immediately after authentication and increments by one per accepted command. It goes into every MAC and every IV, so no two commands in a session look alike.

Here is the rule that costs people an evening:

> **The counter increments right after the card accepts the command, and *before* you verify the response.**
>
> So the **command** MAC uses the pre-increment value, and the **response** MAC and response IV use the post-increment value.

Get this backwards and your command MACs are perfect while every response verification fails — which looks exactly like a broken MAC implementation, sending you back to debug code that's correct.

```
ctr = 0  ──►  build command MAC with ctr=0
              send
              card accepts
              ctr becomes 1     ◄── the increment happens HERE
              verify response MAC with ctr=1
              decrypt response with IV built from ctr=1
```

## The four building blocks

**1. Truncated MAC.** DESFire transmits only 8 of the 16 CMAC bytes — the **odd-indexed** ones: `mac[1], mac[3], mac[5], … mac[15]`. Halves the frame overhead; 64 bits is still plenty against forgery. You can verify this by eye:

```
truncate(00112233445566778899aabbccddeeff) = 1133557799bbddff
```

**2. MAC input layout.** Both directions have the same shape, differing only in byte 0:

```
command:   [cmd ][ctrLo][ctrHi][TI0..TI3][data...]
response:  [0x00][ctrLo][ctrHi][TI0..TI3][data...]
                                  ↑
                     the counter is 2 bytes, LITTLE-endian
```

Byte 0 of the response input is `0x00` — the success status code. You only check a response MAC after a successful status, so it's always zero.

**3. IV derivation.** IVs are computed, not chosen — each is an ECB encryption of a block built from labels, TI and the counter:

```
command IV  = AES-ECB(Kenc, [0xA5, 0x5A, TI0..TI3, ctrLo, ctrHi, 00 × 8])
response IV = AES-ECB(Kenc, [0x5A, 0xA5, TI0..TI3, ctrLo, ctrHi, 00 × 8])
                             └──── note the labels are SWAPPED ────┘
```

Because the counter is in there, **every command gets a unique IV for free**. That's the property CBC needs and it comes out of the counter rather than a separate random value.

**4. ISO 7816-4 padding.** Append `0x80`, then zeros to the next 16-byte boundary. ⚠️ **It always adds at least one byte** — a 16-byte payload becomes 32, not 16. That's what makes the padding unambiguously removable.

```java
int padded = (data.length | 0x0F) + 1;   // next multiple of 16, always > length
```

---

## Build it — Part 1: grow `DesfireSession`

The session started as "two keys." It now becomes the full secure-messaging engine, because that's where the state and the keys already live. **State and the operations on it belong together** — that's the actual definition of encapsulation, and this is a textbook case.

Add to `DesfireSession`:

```java
    // ── EV2 secure-messaging state ───────────────────────────────────
    private final byte[] ti;   // transaction id from auth, 4 bytes
    private int cmdCounter;    // starts at 0, one per command

    // Old constructor: keys only. TI defaults to zeros -- key derivation never
    // uses TI, so the Chapter 8 test keeps passing untouched.
    // (Constructor chaining: this(...) delegates to the fuller one.)
    public DesfireSession(byte[] authKey, byte[] rndA, byte[] rndB) {
        this(authKey, rndA, rndB, new byte[4]);
    }

    public DesfireSession(byte[] authKey, byte[] rndA, byte[] rndB, byte[] ti) {
        if (authKey.length != 16) throw new IllegalArgumentException("auth key must be 16 bytes");
        if (ti.length != 4)       throw new IllegalArgumentException("TI must be 4 bytes");
        this.keyEnc = deriveSessionKey(authKey, rndA, rndB, SessionKeyType.ENC);
        this.keyMac = deriveSessionKey(authKey, rndA, rndB, SessionKeyType.MAC);
        this.ti = ti.clone();
        this.cmdCounter = 0;
    }

    // ── the MAC layer ────────────────────────────────────────────────

    /** Tick the counter. Wraps at 16 bits. Ordering is load-bearing. */
    public void incrementCounter() {
        // TODO: cmdCounter = (cmdCounter + 1) & 0xFFFF;
    }

    /** Keep the ODD-indexed bytes: mac[1], mac[3], ... mac[15]. */
    private static byte[] truncateMac(byte[] mac16) {
        // TODO: byte[8]; out[i] = mac16[i * 2 + 1];
        return new byte[8];
    }

    /** MAC input = [cmd][ctrLo][ctrHi][TI 4][data...], then CMAC + truncate. */
    public byte[] commandMac(byte cmd, byte[] data) {
        // TODO: byte[] input = new byte[7 + data.length];
        //       input[0] = cmd;
        //       input[1] = (byte)(cmdCounter & 0xFF);
        //       input[2] = (byte)((cmdCounter >> 8) & 0xFF);
        //       copy ti into [3..7), data into [7..);
        //       return truncateMac(new AesCmac(keyMac).compute(input));
        return new byte[8];
    }

    /** Same shape, but input[0] = 0x00 (the success status code). */
    public byte[] responseMac(byte[] data) {
        // TODO
        return new byte[8];
    }

    public boolean verifyResponseMac(byte[] data, byte[] mac8) {
        // TODO: Arrays.equals(responseMac(data), mac8)
        return false;
    }

    // ── the crypto layer ─────────────────────────────────────────────

    public byte[] commandIv()  { /* TODO: calcIv(0xA5, 0x5A) */ return new byte[16]; }
    public byte[] responseIv() { /* TODO: calcIv(0x5A, 0xA5) */ return new byte[16]; }

    /** Only the two label bytes differ between the two IVs. */
    private byte[] calcIv(byte b0, byte b1) {
        // TODO: byte[16] block; block[0]=b0; block[1]=b1;
        //       ti into [2..6); block[6]=ctrLo; block[7]=ctrHi; rest zero;
        //       return Aes.ecbEncrypt(keyEnc, block);
        return new byte[16];
    }

    /** ISO 7816-4: append 0x80 then zeros. ALWAYS adds >= 1 byte. */
    private static byte[] padIso7816(byte[] data) {
        // TODO: int padded = (data.length | 0x0F) + 1; ...
        return data;
    }

    public byte[] encryptCommandData(byte[] plaintext) {
        // TODO: Aes.cbcEncrypt(keyEnc, commandIv(), padIso7816(plaintext))
        return new byte[0];
    }

    public byte[] decryptResponseData(byte[] ciphertext) {
        // TODO: Aes.cbcDecrypt(keyEnc, responseIv(), ciphertext)
        //   Padding is left in place -- the caller knows its payload length.
        return new byte[0];
    }
```

> **Note what just happened to the class.** It began as a value object holding two keys. It's now a stateful engine with a counter, an identity, and a dozen operations. That growth is *correct* — the new responsibilities all operate on state it already owned. Had you instead put `cmdCounter` in `DesfireCard` and passed it into every method, you'd have the counter in one class and the keys in another, and every call site would be responsible for keeping them in step. The bug where someone forgets to increment would be *possible*.
>
> **Put the state and its operations in the same place, and whole categories of bug become unrepresentable.**

### Verify the layers

```
session = DesfireSession(authKey, rndA, rndB, TI=11223344)   // Chapter 8's inputs
```

| call | ctr | expected |
|---|---|---|
| `truncateMac(00112233445566778899aabbccddeeff)` | — | `1133557799bbddff` |
| `commandMac(0x6C, {01})` | 0 | `4d131ee17b482b16` |
| `responseMac({})` after one increment | 1 | `6b082e8062d1a89d` |
| `commandIv()` | 0 | `dd42134cd6970980d22066cb00ab1b16` |
| `encryptCommandData(96000000)` | 0 | `a2eb8f000bf3ddfe6e18e8859b284591` |
| `responseIv()` | 1 | `cb73ae774aafd81868062906135969b9` |

```java
DesfireSession s = new DesfireSession(authKey, rndA, rndB, h("11223344"));
assertEquals("4d131ee17b482b16", hex(s.commandMac((byte) 0x6C, h("01"))));
assertEquals("dd42134cd6970980d22066cb00ab1b16", hex(s.commandIv()));
assertEquals("a2eb8f000bf3ddfe6e18e8859b284591", hex(s.encryptCommandData(h("96000000"))));
s.incrementCounter();                                   // ctr -> 1
assertEquals("6b082e8062d1a89d", hex(s.responseMac(new byte[0])));
assertEquals("cb73ae774aafd81868062906135969b9", hex(s.responseIv()));
```

`96 00 00 00` is 150 little-endian — a €1.50 debit amount.

---

## Build it — Part 2: the commands

### ⚠️ First, a one-line fix in Chapter 9's code

Your `authenticateEv2First` currently builds the session **without** the TI:

```java
session = new DesfireSession(key, rndA, rndB);        // TI = 0000 !
```

Change it:

```java
session = new DesfireSession(key, rndA, rndB, ti);
```

Miss this and every MAC from here on is computed against the wrong TI. Symptom: authentication succeeds, then the very first `getValue` fails its response MAC. Genuinely maddening if you don't know.

### `getValue` (0x6C) — read the balance

```
command : 90 6C 00 00 09 [fileNo][cmdMac 8] 00
response: [encrypted 16][respMac 8] 91 00
```

```java
    public int getValue(byte fileNo) throws SpiException {
        // TODO 1: byte[] header = { fileNo };
        //         byte[] mac = session.commandMac((byte) 0x6C, header);   // ctr = 0
        //         send header ++ mac  (9 bytes) via wrapApdu(0x6C, ...)
        // TODO 2: session.incrementCounter();     // BEFORE reading the response
        // TODO 3: drop the 2 status bytes; body must be >= 24;
        //         encrypted = body[0..16), respMac = body[16..24)
        // TODO 4: if (!session.verifyResponseMac(encrypted, respMac))
        //             throw new SpiException("GetValue response MAC failed");
        //   ⚠️ Do NOT skip this. An unverified balance is a number an attacker
        //   chose. The whole point of the MAC is refusing to trust it.
        // TODO 5: byte[] plain = session.decryptResponseData(encrypted);
        //         return decodeValue(plain);      // first 4 bytes, LE signed
        return 0;
    }

    /** Signed 32-bit little-endian from the first 4 bytes. */
    private static int decodeValue(byte[] b) {
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8)
             | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }
```

### `debit` (0xDC) and `commitTransaction` (0xC7)

```
debit    : 90 DC 00 00 19 [fileNo][enc amount 16][cmdMac 8] 00   →  [respMac 8] 91 00
commit   : 90 C7 00 00 08 [cmdMac 8] 00                          →  [respMac 8] 91 00
```

Note the asymmetry: **debit is Full** (the amount is encrypted), **commit is MAC-only** (it carries no data).

```java
    /** Stage a debit. NOTHING MOVES until commitTransaction() succeeds. */
    public void debit(byte fileNo, int amountCents) throws SpiException {
        // TODO 1: byte[] header     = { fileNo };
        //         byte[] ciphertext = session.encryptCommandData(encodeValue(amountCents));
        //         byte[] macInput   = header ++ ciphertext;   // fileNo stays PLAIN
        //         byte[] mac        = session.commandMac((byte) 0xDC, macInput);
        //         send macInput ++ mac
        // TODO 2: requireOk(response); session.incrementCounter();
        // TODO 3: respMac = the last 8 bytes BEFORE the status word;
        //         verify over EMPTY data (the reply carries none).
    }

    /** Make the staged debit permanent. */
    public void commitTransaction() throws SpiException {
        // TODO: mac = session.commandMac((byte) 0xC7, new byte[0]);
        //       send it; requireOk; incrementCounter; verify respMac over empty.
    }

    private static byte[] encodeValue(int v) {
        return new byte[]{ (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) };
    }

    /** Throw unless the status word is 91 00, naming the card's code. */
    private static void requireOk(byte[] response) throws SpiException {
        if (response.length < 2) throw new SpiException("response too short");
        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;
        if (sw1 != 0x91 || sw2 != 0x00) {
            throw new SpiException(String.format("card returned %02X%02X", sw1, sw2));
        }
    }
```

### Why Debit and Commit are separate — the most important idea in this chapter

`Debit` does **not** change the balance. It stages a delta in the card's transaction buffer. Only `CommitTransaction` makes it permanent.

This exists because **a contactless card can leave the field at any instant**, mid-command, with no warning. Consider what a single atomic "debit" command would mean: the card is debited, the reply is lost as the user pulls the card away, the machine never learns it succeeded, and no coffee is dispensed. The customer has paid for nothing and you cannot prove otherwise.

With staging, the failure mode is safe: no commit means the card silently discards the delta and the balance is exactly what it was. **The worst case becomes "nothing happened" instead of "money vanished."**

> That's why the kiosk must only dispense **after** the commit is acknowledged. Dispensing on the debit is giving away coffee for a transaction that may roll back.

The same reasoning is why `PaymentReceipt` in Chapter 11 is only constructed after the commit returns. Design your error paths so that the ambiguous case is the harmless one.

---

## Verify — the full offline replay

Add to your `DesfireCardTest` fake (fresh session → debit runs at ctr 0):

| step | ctr | bytes |
|---|---|---|
| `debit(0x01, 150)` sends | 0 | `01a2eb8f000bf3ddfe6e18e8859b28459127e5ea2cb8acad7d` |
| card's debit reply MAC | 1 | `6b082e8062d1a89d` |
| `commitTransaction()` sends | 1 | `4dc9859e1a783ee0` |
| card's commit reply MAC | 2 | `febea1fcb55208ba` |
| `getValue(0x01)` reply (balance 1000) | 1 | `7950692fc2f01e72b3cee290f7122b5c` + `a44a7f2359b2f512` |

```java
    // extra cases in the fake's transceive switch
    case (byte) 0x6C:                       // GetValue
        return getValueReply;
    case (byte) 0xDC:                       // Debit
        assertArrayEquals(h("01a2eb8f000bf3ddfe6e18e8859b28459127e5ea2cb8acad7d"),
                Arrays.copyOfRange(apdu, 5, apdu.length - 1), "debit bytes wrong");
        return debitReply;
    case (byte) 0xC7:                       // CommitTransaction
        assertArrayEquals(h("4dc9859e1a783ee0"),
                Arrays.copyOfRange(apdu, 5, apdu.length - 1), "commit MAC wrong");
        return commitReply;

    @Test
    public void debitAndCommit_matchesVectors() throws SpiException {
        DesfireCard card = new DesfireCard(this, this);
        card.selectApplication(new byte[]{ 0x01, 0x02, 0x03 });
        card.authenticateEv2First((byte) 0x00, key);    // ctr 0
        card.debit((byte) 0x01, 150);
        card.commitTransaction();
    }
```

> ⚠️ Put debit/commit in a **separate `@Test`** from the getValue one. Each test needs a fresh session, because they disagree about the counter otherwise.

> ⚠️ **The green-test-that-tests-nothing trap.** After wiring the `0x6C` case into your fake, it is entirely possible to have a passing test that never calls `getValue`. The test goes green, you feel good, and nothing was verified. **Check that your test actually calls the method** — the assertion has to be `assertEquals(1000, card.getValue((byte) 0x01))`, not merely a `case` in a switch.

---

## Then: the real thing

```java
int before = desfire.getValue((byte) 0x01);
System.out.printf("balance = %d cents%n", before);

desfire.debit((byte) 0x01, 10);          // stage 10 cents
desfire.commitTransaction();             // make it permanent
int after = desfire.getValue((byte) 0x01);
System.out.printf("%d -> %d cents%n", before, after);
```

**Success:**

```
balance = 510 cents (5.10 EUR)
debiting 10 cents...
510 -> 500 cents (5.10 -> 5.00 EUR)
```

**You just moved real money with code you wrote from the SPI bytes up.** Every layer — register encoding, FIFO handling, anticollision, block framing, CMAC, key derivation, mutual authentication, secure messaging — is yours.

---

## Traps

| Symptom | Cause |
|---|---|
| Auth OK, first getValue fails its MAC | The TI one-liner — session built without TI. |
| Command MACs right, response MACs wrong | Increment ordering. It happens *before* verifying the response. |
| `91 BE` (boundary error) | Debit would take the balance below the file's lower limit. |
| `91 9D` (permission denied) | Authenticated with the wrong key for that file's access rights. |
| `91 F0` (file not found) | The value file doesn't exist yet — the card needs provisioning. |
| Balance unchanged after debit | You forgot `commitTransaction()`. Working as designed. |
| First command works, second fails | Not incrementing, or incrementing twice. |
| Everything fails after one bad run | Card left mid-transaction. Power-cycle the field and re-authenticate. |

---

## Commit

```bash
git add -A
git commit -m "M7d: EV2 secure messaging + GetValue/Debit/Commit -- money moves"
```

---

*Next: [11 — The kiosk UI](11-kiosk.md)*
