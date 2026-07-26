# 05 — Activation

**Goal:** take a card from "something is on the antenna" to "I know its UID and it's ready for commands." End with a real UID printed.

**OOP concept:** value objects and immutability.

---

## The problem: the card's state machine

A contactless card is a state machine, and you must walk it in order:

```
  POWER-OFF ──(enters the field)──▶ IDLE
     ▲                                │
     │                          REQA / WUPA
  (leaves field)                      ▼
     │                              READY
     │                       anticollision + SELECT
     │                                ▼
     │                              ACTIVE
     │                              RATS
     │                                ▼
     └─────────────────────────  ISO-DEP  ◀── you can finally send APDUs
```

`isCardPresent()` from Chapter 3 gets you IDLE → READY. This chapter covers the rest.

### Anticollision, and why it exists

Put two cards on the antenna and both answer at once — the radio signal collides and you get mush. ISO-14443 solves this with a bit-by-bit tree walk: the reader asks "everyone whose UID starts with these bits, answer", and narrows until exactly one card remains.

We implement the **simple case**: ask for the whole UID at once, and if the reply's checksum is intact, exactly one card answered. If two cards collide, the BCC fails, we return empty, and the user is told to present one card. That's honest engineering for a coffee machine.

### Cascade levels, and the 0x88 tag

UIDs come in 4-byte and 7-byte flavours, but an anticollision reply is fixed at 5 bytes (4 data + 1 checksum). So a 7-byte UID needs two rounds:

- **Level 1** (`SEL_CL1 = 0x93`) returns `88 u0 u1 u2` + BCC. That leading `0x88` is the **cascade tag**, meaning "there's more" — it is *not* part of the UID.
- The SAK from level 1 comes back with **bit 2 (`0x04`) set**, confirming "incomplete."
- **Level 2** (`SEL_CL2 = 0x95`) returns `u3 u4 u5 u6` + BCC.
- Real UID = level-1 bytes **1,2,3** (skipping the tag) + all four level-2 bytes = 7 bytes.

DESFire EV2/EV3 always has a 7-byte UID, so you *will* exercise the cascade path.

> ⚠️ Forgetting to skip the `0x88` gives you a 7-byte UID that's wrong in its first byte — and it looks completely plausible. You'll only notice when it doesn't match what your phone reports.

**BCC** is the block check character: `uid[0] ^ uid[1] ^ uid[2] ^ uid[3]`. XOR the four bytes; the result must equal the fifth. A cheap integrity check that catches both RF corruption and undetected collisions.

**RATS** (Request Answer To Select, `0xE0 0x50`) is the final handshake: "let's speak ISO-14443-4." The card replies with its **ATS**, describing its capabilities and maximum frame size. The `0x50` parameter is FSDI=5 (we accept up to 64-byte frames) and CID=0.

---

## Build it

### `ActivatedCard` — a value object

`src/main/java/com/midnightbrewer/hardware/ActivatedCard.java`:

```java
package com.midnightbrewer.hardware;

import java.util.Arrays;

/**
 * What activation produced: a UID, a SAK and an ATS.
 *
 * A VALUE OBJECT -- it holds data, has no behaviour that changes it, and is
 * IMMUTABLE: every field is final and there is no setter. Once built, it can
 * never disagree with itself.
 *
 * Why bother, instead of returning a byte[][] or three out-parameters?
 *   - the three fields arrive together and belong together
 *   - the compiler enforces you can't forget one
 *   - it has a NAME, so signatures read as intent:
 *       ActivatedCard activate()      vs      byte[][] activate()
 */
public final class ActivatedCard {

    private final byte[] uid;
    private final int sak;
    private final byte[] ats;

    public ActivatedCard(byte[] uid, int sak, byte[] ats) {
        // DEFENSIVE COPY on the way in: if we stored the caller's array
        // directly, they could mutate our state afterwards and we would
        // silently change. Copy, and we own it.
        this.uid = uid.clone();
        this.sak = sak;
        this.ats = ats.clone();
    }

    /** Defensive copy on the way OUT too, for the same reason. */
    public byte[] uid() {
        return uid.clone();
    }

    public int sak() {
        return sak;
    }

    public byte[] ats() {
        return ats.clone();
    }

    /** The UID as spaced uppercase hex, e.g. "04 76 18 62 7B 20 90". */
    public String uidHex() {
        // TODO: StringBuilder, String.format("%02X ", b) per byte, trim().
        return null;
    }
}
```

> **`final class`** means nobody may subclass it. For a value object that's right: there is no such thing as a "special kind of ActivatedCard," and allowing subclasses would let someone override `uid()` to return something else, breaking the immutability guarantee.
>
> **Defensive copies both ways** is the part people skip. Arrays in Java are references — handing out `this.uid` hands out a pointer to your internals, and the caller can scribble on it. `clone()` costs 7 bytes and buys you a class that genuinely cannot be corrupted from outside.
>
> **Why immutability matters here:** an `ActivatedCard` is a *fact about a moment* — "at 14:32 this card, with this UID, was activated." Facts don't change. Making it immutable means you can pass it anywhere, store it, log it, hand it to another thread, and never wonder whether something modified it.

### The activation sequence — add to `Iso14443Reader`

These constants go at the top of the base class:

```java
    /** Anticollision/SELECT command bytes for the two cascade levels. */
    private static final byte SEL_CL1 = (byte) 0x93;
    private static final byte SEL_CL2 = (byte) 0x95;

    /** NVB byte that turns an anticollision into a SELECT (full UID follows). */
    private static final byte SELECT_NVB = 0x70;

    /** SAK bit 3: "UID incomplete, do the next cascade level". */
    private static final int CASCADE_BIT = 0x04;

    /** RATS: request ISO-14443-4. PARAM 0x50 = FSDI 5, CID 0. */
    private static final byte RATS_CMD   = (byte) 0xE0;
    private static final byte RATS_PARAM = 0x50;

    /** The tag prefixing a 7-byte UID's first anticollision reply. */
    private static final byte CASCADE_TAG = (byte) 0x88;

    /** Anticollision reply is 4 UID/CT bytes + 1 BCC. */
    private static final int ANTICOLL_LENGTH = 5;
```

And the methods:

```java
    // ═════════════════════════════════════════════════════════════════
    // anticollision -- ask the card for (part of) its UID.
    //
    //   C: serNum[0] = 0x93; serNum[1] = 0x20;      // 0x20 NVB = "send it all"
    //      ToCard(PCD_TRANSCEIVE, serNum, 2, ...);
    //      // verify serNum[0]^[1]^[2]^[3] == serNum[4]
    //
    // Takes selCode so it works at BOTH levels: 0x93 reads the first bytes,
    // 0x95 the rest of a 7-byte UID. Full bytes, so bitsInLastByte = 0.
    // Returns the 5-byte reply, or empty if nothing valid came back.
    // ═════════════════════════════════════════════════════════════════
    public byte[] anticollision(byte selCode) throws SpiException {
        // TODO 1: transceive { selCode, 0x20 } with 0 bits-in-last-byte.
        // TODO 2: a valid reply is exactly ANTICOLL_LENGTH bytes; else empty.
        // TODO 3: verify the BCC (XOR of the first four == the fifth);
        //         if it fails the read was garbled -> return empty.
        // TODO 4: return the reply.
        return new byte[0];
    }

    // ═════════════════════════════════════════════════════════════════
    // select -- send the UID back, receive the SAK.
    //
    //   C: buffer[0]=selCode; buffer[1]=0x70;          // 0x70 NVB = SELECT
    //      memcpy(&buffer[2], anticoll, 5);            // 4 UID + BCC
    //      CalculateCRC(buffer, 7, &buffer[7]);        // -> 9 bytes
    //      ToCard(...);   // reply = SAK(1) + CRC(2)
    //
    // @return the SAK byte, or -1 if the reply was malformed.
    // ═════════════════════════════════════════════════════════════════
    public int select(byte selCode, byte[] anticoll) throws SpiException {
        // TODO 1: build a 7-byte frame { selCode, SELECT_NVB, ...anticoll(5) }.
        // TODO 2: byte[] crc = calculateCrc(frame7);
        //         build a 9-byte frame: the 7, then crc[0], crc[1].
        // TODO 3: byte[] reply = transceive(frame9, 0);
        // TODO 4: if reply.length < 1 return -1, else return reply[0] & 0xFF.
        return -1;
    }

    // ═════════════════════════════════════════════════════════════════
    // rats -- ask to speak ISO-14443-4; the card returns its ATS.
    //   C: req[0]=0xE0; req[1]=0x50; CalculateCRC(req,2,&req[2]); ToCard(...)
    // ═════════════════════════════════════════════════════════════════
    public byte[] rats() throws SpiException {
        // TODO: header { RATS_CMD, RATS_PARAM }, append its CRC, transceive.
        return new byte[0];
    }

    // ═════════════════════════════════════════════════════════════════
    // activate() -- the whole sequence, as another TEMPLATE METHOD.
    //
    // 'final' so a subclass cannot reorder the protocol. It orchestrates the
    // steps above:
    //   level 1: anticollision -> select -> SAK
    //   if the SAK's cascade bit is set, the UID is 7 bytes: do level 2 too
    //   then RATS
    // and assembles the real UID:
    //   - 4-byte card: ac1[0..3]
    //   - 7-byte card: ac1[1..3]  (SKIP the 0x88 cascade tag)  +  ac2[0..3]
    // ═════════════════════════════════════════════════════════════════
    public final ActivatedCard activate() throws SpiException {
        // TODO 1: ac1 = anticollision(SEL_CL1); if empty -> throw SpiException.
        //         sak1 = select(SEL_CL1, ac1);
        // TODO 2: if ((sak1 & CASCADE_BIT) == 0) -> 4-byte UID:
        //         uid = ac1[0..3], return new ActivatedCard(uid, sak1, rats());
        // TODO 3: else 7-byte UID: ac2 = anticollision(SEL_CL2);
        //         sak2 = select(SEL_CL2, ac2);
        //         uid = { ac1[1], ac1[2], ac1[3], ac2[0], ac2[1], ac2[2], ac2[3] }
        //         return new ActivatedCard(uid, sak2, rats());
        return null;
    }
```

---

## Verify on hardware

```java
if (reader.isCardPresent()) {              // wake the card into READY first
    ActivatedCard card = reader.activate();
    System.out.println("UID  = " + card.uidHex());
    System.out.printf("SAK  = 0x%02X%n", card.sak());
    System.out.println("ATS  = " + hex(card.ats()));
}
```

Expected, for a DESFire EV3:

```
UID  = 04 76 18 62 7B 20 90
SAK  = 0x20
ATS  = 06 75 77 81 02 80 02 F0
```

**How to read that output:**

- **UID starts `04`** — NXP's manufacturer byte. Seven bytes total, so the cascade path ran and you skipped the tag correctly.
- **`SAK = 0x20`** — bit 5 set means "ISO-14443-4 compliant." This is the card saying *I speak APDUs*. A MIFARE Classic answers `0x08`; if you see that, you have the wrong card type and nothing after this chapter will work.
- **ATS `06 75 77 81 02 80 02 F0`** — `06` is its length, and the `75` encodes FSCI=5 → a 64-byte maximum frame.

> ⚠️ **The FSCI nibble.** It is the **low** nibble of TA(1)-adjacent formatting in the ATS, and it is genuinely easy to read the wrong nibble — the original C in this project had exactly that bug. Reading the high nibble gives you FSCI=7 → "128-byte frames", you send a 100-byte APDU, and the card silently ignores it. Symptom: everything works until one particular command, which times out forever.

---

## Traps

| Symptom | Cause |
|---------|-------|
| UID is 7 bytes but wrong in byte 0 | Didn't skip the `0x88` cascade tag. |
| `select()` returns −1 | CRC wrong. Check `ModeReg = 0x3D` (preset `0x6363`). |
| Anticollision returns empty always | Card not in READY — call `isCardPresent()` first. |
| `SAK = 0x08` | It's a MIFARE Classic, not a DESFire. Wrong card. |
| RATS returns empty | Card doesn't support ISO-14443-4, or it's already in ISO-DEP (RATS twice). |
| Works, then fails on retry | Card left in ACTIVE. Power-cycle the field (`softReset` + `antennaOn`) per transaction. |

---

## Commit

```bash
git add -A
git commit -m "M5: activation -- anticollision, SELECT, cascade, RATS, ActivatedCard"
```

---

*Next: [06 — The APDU channel](06-apdu-channel.md)*
