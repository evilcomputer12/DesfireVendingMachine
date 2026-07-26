# 06 — The APDU channel

**Goal:** send your first real DESFire command and get an answer. End with the card reporting its own chip type.

**OOP concept:** composition and layering — building the last rung of the ladder.

---

## The problem

The card is activated. Now you want to send it commands like "select application" or "give me the balance." Those are **APDUs** (Application Protocol Data Units), and they need wrapping:

```
your APDU:   90 60 00 00 00
                  ↓ wrap in an I-block (Chapter 4)
frame:       02 90 60 00 00 00
                  ↓ append CRC (Chapter 3)
on the wire: 02 90 60 00 00 00 <crc> <crc>
```

and the reply comes back the same way in reverse. Someone has to own that wrapping — and the block-number bookkeeping that goes with it.

### The block number: a one-bit conversation counter

ISO-DEP puts a single bit in the I-block PCB that **alternates 0, 1, 0, 1…** with each exchange. It exists to disambiguate retransmissions: if a reply is lost and you resend, the card can tell "this is the same command again" from "this is the next command."

You must toggle it after every exchange. Get out of sync and the card ignores you — which looks exactly like a dead card.

> This is precisely the kind of state that should be **private and automatic**. If the block number were a parameter every caller had to pass, someone would eventually get it wrong. Hide it inside the channel; make it impossible to get wrong from outside.

### DESFire's APDU wrapping

DESFire natively speaks its own short frames, but also accepts them wrapped in ISO-7816 APDUs, which is what we use:

```
90  INS  00  00  Lc  <data...>  00
│   │    │   │   │              └── Le: "send me back as much as you have"
│   │    │   │   └── Lc: length of data
│   │    └───┴── P1, P2: always 00 for DESFire
│   └── the DESFire command byte (0x60 = GetVersion, 0x5A = SelectApplication…)
└── CLA: 0x90 means "DESFire native command, wrapped"
```

Replies end in a two-byte status word: **`91 00`** = OK, **`91 AF`** = "more frames follow", and various `91 xx` errors.

---

## Build it

### `ApduChannel` — another seam

`src/main/java/com/midnightbrewer/hardware/ApduChannel.java`:

```java
package com.midnightbrewer.hardware;

/**
 * "Something that can carry an APDU to a card and bring back the reply."
 *
 * The same trick as SpiLink, one layer up. The DESFire logic you write in
 * Chapters 7-10 will depend only on THIS -- so it can be tested against a
 * fake channel replaying canned card replies, with no reader and no card.
 *
 * That is not a nicety. Crypto is the one thing you cannot debug by staring at
 * it, and this interface is what makes it testable offline.
 */
public interface ApduChannel {

    /** Send one APDU, return the response (including its status word). */
    byte[] transceive(byte[] apdu) throws SpiException;
}
```

One method. That's the whole contract, and it's enough to make Part 3 testable.

### `InformationBlock.encode` — the block gains behaviour

Back in Chapter 4 an I-block could only `describe()` itself. Now it does real work:

```java
    /**
     * Encode this I-block into a frame: its PCB, then the data it carries.
     */
    public byte[] encode(byte[] inf) {
        // TODO: byte[frame] of length inf.length + 1;
        //       frame[0] = (byte) pcb();
        //       copy inf in after it; return frame.
        return null;
    }
```

> Worth noticing: the class you built two chapters ago, which seemed like an academic exercise in polymorphism, is now the thing that formats your wire frames. That's how designs pay off — the structure was right before you knew what you'd need it for.

### `IsoDepChannel` — the implementation

`src/main/java/com/midnightbrewer/hardware/IsoDepChannel.java`:

```java
package com.midnightbrewer.hardware;

import java.util.Arrays;

/**
 * The ISO-14443-4 (ISO-DEP) channel: one APDU at a time, wrapped in an
 * I-block.
 *
 * The OOP on show:
 *   - COMPOSITION: it HAS-A reader (a channel is not a kind of reader).
 *   - ENCAPSULATED STATE: the block number toggles 0<->1 and nobody outside
 *     can touch it, or forget to.
 *   - it USES the M4 hierarchy: an InformationBlock encodes the frame.
 *
 * Simplifications (fine for DESFire's small frames): one I-block per APDU, no
 * chaining of large payloads, and no S-block "wait" handling.
 */
public class IsoDepChannel implements ApduChannel {

    /** I-block PCB base; the low bit carries the block number. */
    private static final int IBLOCK_PCB = 0x02;

    private final Iso14443Reader reader;  // composition
    private int blockNumber = 0;          // private mutable state, owned here

    public IsoDepChannel(Iso14443Reader reader) {
        this.reader = reader;
    }

    /**
     *   1. wrap the APDU in an I-block for the current block number
     *   2. send it via the reader (which appends CRC and transceives)
     *   3. toggle the block number for next time
     *   4. the reply is [PCB, ...response...] -- strip the PCB
     */
    @Override
    public byte[] transceive(byte[] apdu) throws SpiException {
        // TODO 1: InformationBlock out = new InformationBlock(IBLOCK_PCB | blockNumber);
        //         byte[] frame = out.encode(apdu);
        // TODO 2: byte[] reply = reader.exchange(frame);
        // TODO 3: blockNumber ^= 1;    // toggle -- XOR with 1 flips a bit
        // TODO 4: if (reply.length < 1) throw new SpiException("empty ISO-DEP reply");
        //         return Arrays.copyOfRange(reply, 1, reply.length);  // drop PCB
        return new byte[0];
    }
}
```

### `Iso14443Reader.exchange` — the reusable primitive

You wrote "append a CRC and transceive" twice already, inside `select()` and `rats()`. Pull it out:

```java
    // ═════════════════════════════════════════════════════════════════
    // exchange() -- send a payload as a CRC-protected T=CL frame, return the
    // reply with its CRC stripped.
    //
    // The ISO-DEP channel sits on top of this: it hands us [PCB + apdu], we
    // handle the CRC and the wire.
    // ═════════════════════════════════════════════════════════════════
    public byte[] exchange(byte[] payload) throws SpiException {
        // TODO 1: byte[] crc = calculateCrc(payload);
        // TODO 2: build frame = payload followed by crc[0], crc[1].
        // TODO 3: byte[] reply = transceive(frame, 0);
        // TODO 4: if (reply.length < 2) return new byte[0];
        //         return Arrays.copyOf(reply, reply.length - 2);   // drop reply CRC
        return new byte[0];
    }
```

---

## The layer cake, complete

You've now built six layers, each speaking only to its neighbours:

```
    DesfireCard          "debit 1.50 from the wallet"      ← Chapters 7-10
        │  ApduChannel  ← the seam that makes crypto testable
        ▼
    IsoDepChannel        "wrap this APDU in an I-block"    ← this chapter
        ▼
    Iso14443Reader       "append a CRC, send this frame"   ← Ch. 3 & 5
        ▼
    Rc522Reader          "load the FIFO, press send"       ← Ch. 3
        ▼
    Rc522Driver          "write register 0x0D = 0x80"      ← Ch. 2
        │  SpiLink      ← the seam that makes the driver testable
        ▼
    Pi4jSpiLink          "shift these two bytes"           ← Ch. 1
```

**Every arrow points down, and there are exactly two seams.** The value of that shape: any layer can be tested by faking the layer below, and any layer can be replaced without touching the others. Swap `Pi4jSpiLink` for an FT232H — nothing above changes. Swap the RC522 for a PN532 — write one new `Iso14443Reader` subclass; nothing above changes.

> This is what people mean by "good architecture", and it isn't a matter of taste. It's the property that lets you debug one layer at a time instead of debugging six at once.

---

## Verify on hardware — your first conversation with a DESFire

```java
IsoDepChannel channel = new IsoDepChannel(reader);
// ISO-7816-wrapped DESFire GetVersion: 90 60 00 00 00
byte[] getVersion = { (byte) 0x90, 0x60, 0x00, 0x00, 0x00 };
byte[] resp = channel.transceive(getVersion);
System.out.println("GetVersion resp = " + hex(resp));
```

Expected:

```
GetVersion resp = 04 01 01 33 00 18 05 91 AF
```

**Decode it — this is your card's identity card:**

| Byte | Value | Meaning |
|---|---|---|
| `04` | vendor | NXP |
| `01 01` | type / subtype | DESFire |
| **`33`** | **version** | **major 3 = EV3.** (`0x12` would be EV1, `0x22` EV2.) |
| `00 18` | storage size | `0x18` → 8 KB |
| `05` | protocol | ISO-14443-2/3/4 |
| `91 AF` | status | **"additional frame available"** |

**`91 AF` is not an error** — it's the card saying "I have more to tell you." `GetVersion` chains three frames (hardware info, software info, then UID/batch). You'd send `90 AF 00 00 00` twice more to drain them.

That matters practically: if you leave the card mid-chain and send a different command, some cards abort with an error. Either drain the chain or power-cycle the field before the next command.

**If the `33` byte reads `0x12` or `0x22`,** you have an EV1 or EV2. EV2 works fine with everything in this book. EV1 does *not* support `AuthenticateEV2First` — you'd need the legacy `0x0A`/`0xAA` authentication instead, which is a different algorithm.

---

## Traps

| Symptom | Cause |
|---------|-------|
| Empty reply | Block number out of sync, or RATS never ran. |
| Works once per power-up | Not toggling the block number. |
| `91 AE` on a later command | Card was mid-chain from `91 AF`. Drain it or reset the field. |
| `6E 00` / `6D 00` instead of `91 xx` | ISO-7816 error — CLA/INS wrong. Check the `90` and the `00` Le byte. |
| Reply is one byte too long | Forgot to strip the PCB in `IsoDepChannel`. |

---

## Commit

```bash
git add -A
git commit -m "M6: ApduChannel seam + IsoDepChannel; GetVersion works on silicon"
```

---

*Next: [07 — AES-CMAC from scratch](07-cmac.md) — where the crypto starts.*
