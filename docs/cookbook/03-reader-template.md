# 03 — The reader base class

**Goal:** send a frame to a card and get its answer. End with `isCardPresent()` detecting a real DESFire.

**OOP concept:** abstract class, and the Template Method pattern.

---

## The problem

You can read registers. Now you need to *talk to a card*, which means a fixed sequence:

1. tell the chip how many bits are in the last byte,
2. flush the FIFO, clear old interrupt flags,
3. load the outgoing bytes into the FIFO,
4. start the transceive command,
5. wait for a reply or a timeout,
6. check the error register,
7. read the reply out of the FIFO.

Here's the thing: **steps 1–7 are RC522-specific, but the protocol above them is not.** "Send REQA, expect a 2-byte ATQA" is ISO-14443, identical on a PN532, a PN5180, or an ACR122. If you wrote the protocol logic *inside* an RC522 class, you'd have to rewrite all of it to support a second reader chip.

## The idea: leave a hole in the middle of the algorithm

Write the protocol once, in a base class, and leave the one chip-specific step **blank** — a method with a name and a signature but no body. Subclasses fill the blank. The base class calls the blank as if it were already written.

That's the **Template Method** pattern: the parent owns the *shape* of the algorithm, children supply the *steps*.

> **Analogy.** A recipe for a stir-fry: heat oil, **cook the protein**, add vegetables, add sauce, serve. The sequence is fixed — you never add sauce before the oil is hot. But "cook the protein" is a hole: chicken takes six minutes, prawns take two, tofu wants a press first. The cookbook writes the sequence; the specific dish fills the hole.
>
> Critically, the recipe *calls* the hole without knowing what goes in it. `isCardPresent()` calls `transceive()` — and at runtime that call lands in `Rc522Reader`, or `Pn532Reader`, or a fake. The base class never learns which.

### Why an abstract *class* and not an interface

An interface can hold method signatures but (traditionally) no implementation and no fields. Here you need **both**: real shared code (the protocol) *and* a blank (the chip step). That mix is exactly what an abstract class is for.

| | interface | abstract class |
|---|---|---|
| Method signatures | ✅ | ✅ |
| Real method bodies | ❌ (mostly) | ✅ |
| Fields / state | ❌ | ✅ |
| Can be instantiated | ❌ | ❌ |
| A class can have many | ✅ | ❌ (one parent) |

Rule of thumb: **interface = "can do this"** (a capability, like `SpiLink`). **Abstract class = "is a kind of this"** (a family with shared machinery, like `Iso14443Reader`).

---

## Build it

### The base

`src/main/java/com/midnightbrewer/hardware/Iso14443Reader.java`:

```java
package com.midnightbrewer.hardware;

/**
 * An abstract base for any ISO-14443 reader.
 *
 * The protocol logic (how you wake a card and read its answer) is a standard:
 * identical for an RC522, a PN532, anything. That logic lives HERE, written
 * once. What differs per chip is only how you physically push a frame out and
 * read the reply -- and that is left BLANK for each chip's subclass to fill.
 */
public abstract class Iso14443Reader {

    // ── ISO 14443-3 constants (chip-independent) ─────────────────────
    private static final byte REQA = 0x26; // "any IDLE card, announce yourself"
    private static final byte WUPA = 0x52; // "any IDLE *or* HALTed card, wake up"

    /** A valid ATQA answer is exactly two bytes. */
    private static final int ATQA_LENGTH = 2;

    /** REQA/WUPA are 7-bit short frames, not whole bytes. */
    private static final int REQA_BITS = 7;

    // ═════════════════════════════════════════════════════════════════
    // THE BLANK. 'abstract' = declared, no body. A subclass MUST supply
    // one or it will not compile.
    //
    //   sendData       : bytes to transmit
    //   bitsInLastByte : valid bits in the final byte (7 for REQA, 0 = all 8)
    //   returns        : bytes received, or an EMPTY array if nothing answered.
    //
    // Note "empty array" rather than an exception: a timeout is NORMAL when no
    // card is on the antenna. Exceptions are for the exceptional.
    // ═════════════════════════════════════════════════════════════════
    protected abstract byte[] transceive(byte[] sendData, int bitsInLastByte)
            throws SpiException;

    /**
     * The second blank: compute the 2-byte CRC_A over data.
     *
     * SELECT and RATS need a CRC appended; anticollision does not. The RC522
     * has a hardware CRC unit, another chip might not -- so, like transceive,
     * it is left for the subclass. An abstract class may carry as many blanks
     * as the design needs.
     *
     * @return two CRC bytes, low first, ready to append.
     */
    protected abstract byte[] calculateCrc(byte[] data) throws SpiException;

    // ═════════════════════════════════════════════════════════════════
    // THE TEMPLATE METHOD -- the fixed algorithm, shared by all chips.
    //
    // 'final' on purpose: a subclass may fill the blanks, but may NOT rewrite
    // the sequence. The protocol is the protocol.
    // ═════════════════════════════════════════════════════════════════
    public final boolean isCardPresent() throws SpiException {
        // TODO 1: try REQA; if that finds a card, return true.
        // TODO 2: otherwise try WUPA and return that result.
        //   Why both? REQA only wakes cards in IDLE. A card you already talked
        //   to sits in HALT and ignores REQA forever -- WUPA wakes those too.
        //   Without this, the reader works once and then goes "blind" until
        //   the card is physically removed and replaced.
        return false;
    }

    /** Sends one REQA/WUPA and reports whether a well-formed ATQA came back. */
    private boolean request(byte mode) throws SpiException {
        // TODO: transceive a single-byte frame { mode } with REQA_BITS bits,
        //       and return whether the reply length == ATQA_LENGTH.
        return false;
    }
}
```

> **`protected`** means "visible to this class and its subclasses, but not to the outside world." `transceive` is an implementation detail of the reader family — the kiosk should never call it directly. `public` for the vocabulary, `protected` for the family, `private` for the class alone.

> **Why `final` on `isCardPresent()`?** So a subclass can't override it and reorder the protocol. This encodes a design decision *in the type system*: "chips vary, the standard doesn't." Deciding what subclasses may and may not change is most of what designing a base class actually is.

### The RC522 subclass

`src/main/java/com/midnightbrewer/hardware/Rc522Reader.java`:

```java
package com.midnightbrewer.hardware;

/**
 * The RC522's answer to the two blanks in Iso14443Reader.
 *
 * 'extends' = inheritance: this IS-A Iso14443Reader, and gets all its protocol
 * code for free. It only supplies the two chip-specific steps.
 */
public class Rc522Reader extends Iso14443Reader {

    // ── registers this needs (MFRC522 datasheet) ─────────────────────
    private static final int COMMAND_REG     = 0x01;
    private static final int COMM_IEN_REG    = 0x02; // which IRQs are enabled
    private static final int COMM_IRQ_REG    = 0x04; // which IRQs have fired
    private static final int ERROR_REG       = 0x06;
    private static final int FIFO_DATA_REG   = 0x09; // the FIFO, in and out
    private static final int FIFO_LEVEL_REG  = 0x0A; // how many bytes in the FIFO
    private static final int BIT_FRAMING_REG = 0x0D; // TxLastBits + StartSend

    private static final int PCD_IDLE        = 0x00; // "cancel / do nothing"
    private static final int PCD_TRANSCEIVE  = 0x0C; // "send the FIFO, then receive"

    // ── CRC coprocessor ──────────────────────────────────────────────
    private static final int DIV_IRQ_REG      = 0x05; // CRCIrq is bit 2
    private static final int CRC_RESULT_REG_L = 0x22;
    private static final int CRC_RESULT_REG_H = 0x21;
    private static final int PCD_CALCCRC      = 0x03;

    // COMPOSITION again: the reader HAS-A driver. Note it does NOT extend
    // Rc522Driver -- a reader is not a kind of driver, it USES one.
    private final Rc522Driver driver;

    public Rc522Reader(Rc522Driver driver) {
        this.driver = driver;
    }

    @Override
    protected byte[] transceive(byte[] sendData, int bitsInLastByte) throws SpiException {

        // ── 1. how many bits are valid in the last byte ──────────────
        //   C: Write(BitFramingReg, 0x07);   // 7 for REQA
        //   The low 3 bits of BitFramingReg are TxLastBits.
        // TODO: writeRegister(BIT_FRAMING_REG, bitsInLastByte & 0x07);

        // ── 2. arm the chip and empty the FIFO ───────────────────────
        //   C: Write(CommIEnReg, 0x77|0x80);  // enable IRQs
        //      ClearBitMask(CommIrqReg, 0x80); // clear old flags
        //      SetBitMask(FIFOLevelReg, 0x80); // flush FIFO
        //      Write(CommandReg, PCD_IDLE);    // cancel any running command
        // TODO: those four calls.

        // ── 3. load the outgoing bytes into the FIFO ─────────────────
        // TODO: a loop writing each byte of sendData to FIFO_DATA_REG
        //       (as an int: sendData[i] & 0xFF).

        // ── 4. run it, and press "start sending" ─────────────────────
        //   C: Write(CommandReg, PCD_TRANSCEIVE);
        //      SetBitMask(BitFramingReg, 0x80);   // StartSend = bit 7
        // TODO: those two.

        // ── 5. wait for the answer, or a timeout ─────────────────────
        //   ⚠️ The C spins a large counter. That does NOT port. In C each
        //   register read is a few hundred nanoseconds; here it is a syscall
        //   through the kernel, ~50 µs. The same iteration count runs for
        //   MINUTES. Use a WALL-CLOCK deadline:
        //
        //   long deadline = System.nanoTime() + 50_000_000L;   // ~50 ms
        //   int irq;
        //   do {
        //       irq = driver.readRegister(COMM_IRQ_REG);
        //   } while (System.nanoTime() < deadline
        //            && (irq & 0x01) == 0      // TimerIRq  -> nobody answered
        //            && (irq & 0x30) == 0);    // RxIRq|IdleIRq -> done
        // TODO: the loop. Keep 'irq' -- step 6 needs it.

        // ── 6. stop sending, then check for trouble ──────────────────
        //   C: ClearBitMask(BitFramingReg, 0x80);
        //      if (Read(ErrorReg) & 0x1B) ...   // buffer overflow, parity, CRC, protocol
        //   Also: if TimerIRq fired, nobody answered.
        //   Either way return an EMPTY array -- "no card" is not an error.
        // TODO.

        // ── 7. read the reply out of the FIFO ────────────────────────
        //   C: n = Read(FIFOLevelReg);
        //      for (i=0;i<n;i++) backData[i] = Read(FIFODataReg);
        // TODO: read the level into n, allocate byte[n], loop reading
        //       FIFO_DATA_REG into each slot, return it.
        //   Note: you read the SAME register n times. It is a FIFO -- each
        //   read pops the next byte. This looks wrong and is right.
        return new byte[0];
    }

    @Override
    protected byte[] calculateCrc(byte[] data) throws SpiException {
        //   C: Write(CommandReg, PCD_IDLE);
        //      ClearBitMask(DivIrqReg, 0x04);   // clear CRCIrq
        //      SetBitMask(FIFOLevelReg, 0x80);  // flush FIFO
        //      for (i..) Write(FIFODataReg, data[i]);
        //      Write(CommandReg, PCD_CALCCRC);
        //      wait for DivIrqReg bit 2
        //      out[0] = Read(CRCResultRegL);  out[1] = Read(CRCResultRegH);
        //
        // TODO all of it. Same wall-clock deadline trick (~20 ms is plenty).
        // Return { low, high } -- ISO-14443 puts the CRC on the wire low byte
        // first, and returning it wire-ready means callers just append it.
        return new byte[2];
    }
}
```

---

## The two ports that don't survive translation

**1. Iteration counts become deadlines.** The single most important lesson in porting embedded C to Linux. `for (i = 0; i < 2000; i++)` on bare metal is microseconds. Here, each iteration crosses into the kernel. Always time out on `System.nanoTime()`, never on a loop counter.

**2. Chip-select is not yours any more.** The C toggles CS around each byte. Your `SpiLink.transfer()` already does one complete, atomic transaction. If you try to manage CS yourself, you'll fight the driver and lose.

---

## Verify on hardware

Add to `HardwareCheck.main`, after the version check:

```java
Rc522Reader reader = new Rc522Reader(driver);
System.out.println("Polling isCardPresent() 15x (put a card on the antenna)...");
int hits = 0;
for (int i = 0; i < 15; i++) {
    if (reader.isCardPresent()) hits++;
    Thread.sleep(40);
}
System.out.printf("card detected in %d / 15 polls%n", hits);
```

You need the full init sequence before polling — the datasheet's timer and modulation defaults are not usable:

```java
private static void initChip(Rc522Driver driver) throws SpiException {
    driver.writeRegister(0x2A, 0x8D); // TModeReg     } response timer,
    driver.writeRegister(0x2B, 0x3E); // TPrescalerReg} ~300 ms at 6.78 MHz
    driver.writeRegister(0x2C, 0x02); // TReloadRegH  } (0x0258 = 600 ticks)
    driver.writeRegister(0x2D, 0x58); // TReloadRegL  }
    driver.writeRegister(0x15, 0x40); // TxASKReg  -> force 100% ASK modulation
    driver.writeRegister(0x11, 0x3D); // ModeReg   -> CRC preset 0x6363
    driver.writeRegister(0x26, 0x08); // RFCfgReg  -> receiver gain
    driver.antennaOn();
}
```

Two of those matter enormously:
- **`TxASKReg = 0x40`** forces 100 % ASK modulation, which Type A cards require. Without it the card never sees a valid signal and you get silence that looks like a wiring fault.
- **`ModeReg = 0x3D`** sets the CRC preset to `0x6363`, the ISO-14443-A value. Wrong preset → every CRC you compute is wrong → every SELECT is rejected.

**Success: `card detected in 15 / 15 polls`.**

If you get `0/15` with a card sitting on the antenna: check the init sequence first (especially those two registers), then confirm the card is actually 13.56 MHz.

---

## Traps

| Symptom | Cause |
|---------|-------|
| Runs for minutes, no output | Ported the C's iteration counter. Use a nanoTime deadline. |
| `0/15` polls, chip version fine | Missing `TxASKReg = 0x40` or `ModeReg = 0x3D`. |
| Detects once, then never again | Only sending REQA. Halted cards need WUPA. |
| Detection works ~50 % | Wire length, or card not flat on the antenna. |
| `IllegalArgumentException: negative array size` | FIFO level read before the reply arrived — check the wait loop. |

---

## Commit

```bash
git add -A
git commit -m "M3: Iso14443Reader template method + Rc522Reader transceive/CRC"
```

---

*Next: [04 — Protocol blocks](04-blocks.md)*
