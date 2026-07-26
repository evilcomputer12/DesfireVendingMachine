# 04 — Protocol blocks

**Goal:** model the three ISO-14443-4 frame types as a small class hierarchy.

**OOP concept:** inheritance, polymorphism, and the factory method.

---

## The problem

Once a card is activated you exchange **T=CL blocks**. Every frame starts with a PCB (Protocol Control Byte) whose top bits say which of three kinds it is:

| PCB bits | Type | Carries | Purpose |
|---|---|---|---|
| `0xxxxxxx` | **I-block** (Information) | your APDU | the actual data |
| `10xxxxxx` | **R-block** (Receive ready) | nothing | acknowledgement |
| `11xxxxxx` | **S-block** (Supervisory) | control info | "wait longer", "deselect" |

The C approach is a switch statement, and it works fine — *once*. The problem is what happens over time: you need `describe()` for logging, so that's one switch. Then `expectsResponse()`, another switch. Then `encode()`, a third. Now three switches must be kept in agreement, and when a fourth block type appears you must find all of them.

That's the smell: **the type of a thing is being re-decided at every place you use it.**

## The idea: decide the type once, then let the object know what it is

Make each block type a class. Decide which one you're holding *exactly once*, at the moment the byte arrives. Afterwards you hold an object that already knows what it is, and you just call methods on it.

> **Analogy.** A parcel arriving at a depot. You could sort every parcel at every stage — check "is this fragile?" at the truck, again at the belt, again at the van. Or you sort once at intake, put it in the right bin, and afterwards every stage just handles "whatever's in this bin" by its bin's rules. One decision point, made once, near the door.
>
> The factory method is the intake desk. Polymorphism is the bin's rules applying automatically.

**Polymorphism** literally means "many shapes." You call `block.describe()`. Java looks at what the object *actually is* at runtime — I, R or S — and runs that class's version. One call site, three behaviours, and no `if` anywhere.

---

## Build it

### The base

`src/main/java/com/midnightbrewer/hardware/ProtocolBlock.java`:

```java
package com.midnightbrewer.hardware;

/**
 * One ISO-14443-4 (T=CL) block.
 *
 * All three types share the PCB, so that state lives here in the base -- which
 * is exactly why this is an abstract CLASS and not an interface (an interface
 * cannot hold the field).
 *
 * What DIFFERS is behaviour, so describe() is abstract: each subclass answers
 * it its own way. One method name, three behaviours, chosen by the real type.
 */
public abstract class ProtocolBlock {

    private final int pcb;

    // 'protected' -- only subclasses construct blocks, via the factory below.
    protected ProtocolBlock(int pcb) {
        this.pcb = pcb & 0xFF;
    }

    /** The raw PCB byte (0..255). */
    public int pcb() {
        return pcb;
    }

    /** Declared here, no body -- every subclass MUST answer. */
    public abstract String describe();

    // ═════════════════════════════════════════════════════════════════
    // THE FACTORY -- the ONE place the type is decided.
    //
    // This switch exists once, here. Everywhere else you hold a
    // ProtocolBlock and call methods on it. No type checks, no instanceof.
    //
    //   b8 = 0         -> I-block
    //   b8 = 1, b7 = 0 -> R-block
    //   b8 = 1, b7 = 1 -> S-block
    // ═════════════════════════════════════════════════════════════════
    public static ProtocolBlock from(int pcb) {
        // TODO 1: if bit 7 (0x80) is clear      -> new InformationBlock(pcb)
        // TODO 2: else if bit 6 (0x40) is clear -> new ReceiveReadyBlock(pcb)
        // TODO 3: else                          -> new SupervisoryBlock(pcb)
        return null;
    }
}
```

> **Why a `static` factory instead of `new`?** Because the caller doesn't know which class it wants — that's precisely what it's asking. `ProtocolBlock.from(0x02)` says "give me whatever block this byte represents." The caller states intent; the factory decides the type.
>
> This is the **Open/Closed Principle** in miniature: to add a block type you add a class and one line in the factory. Every existing call site keeps working, untouched. Compare the switch-everywhere version, where you'd hunt through the codebase.

### The three subclasses

```java
package com.midnightbrewer.hardware;

public class InformationBlock extends ProtocolBlock {

    public InformationBlock(int pcb) {
        super(pcb);          // hand the PCB up to the base to store
    }

    @Override
    public String describe() {
        // TODO: "I-block (data), block number N"
        //       The block number is the low bit: pcb() & 0x01
        return null;
    }
}
```

```java
package com.midnightbrewer.hardware;

public class ReceiveReadyBlock extends ProtocolBlock {

    public ReceiveReadyBlock(int pcb) {
        super(pcb);
    }

    @Override
    public String describe() {
        // TODO: "R-block (ACK)" or "R-block (NAK)" -- bit 4 (0x10) set = NAK.
        return null;
    }
}
```

```java
package com.midnightbrewer.hardware;

public class SupervisoryBlock extends ProtocolBlock {

    public SupervisoryBlock(int pcb) {
        super(pcb);
    }

    @Override
    public String describe() {
        // TODO: bits 5..4 (mask 0x30) say which:
        //         0x00 -> "S-block (DESELECT)"
        //         0x30 -> "S-block (WTX -- card asking for more time)"
        //         else -> "S-block (unknown)"
        return null;
    }
}
```

**`super(pcb)`** calls the parent's constructor. It must be the first statement — the base must be fully built before the subclass adds to it. Foundations before walls.

**`@Override`** is optional but always worth writing. It tells the compiler "I mean to be replacing a parent method." If you typo `describ()`, you get a compile error instead of a method that silently never gets called. Free insurance.

---

## Verify

```java
package com.midnightbrewer.hardware;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolBlockTest {

    @Test
    void factoryPicksTheRightType() {
        assertTrue(ProtocolBlock.from(0x02) instanceof InformationBlock);
        assertTrue(ProtocolBlock.from(0x03) instanceof InformationBlock);
        assertTrue(ProtocolBlock.from(0xA2) instanceof ReceiveReadyBlock);
        assertTrue(ProtocolBlock.from(0xF2) instanceof SupervisoryBlock);
    }

    @Test
    void polymorphicDescribe() {
        // Same variable type, same method call, three different answers.
        ProtocolBlock[] blocks = {
            ProtocolBlock.from(0x02),
            ProtocolBlock.from(0xA2),
            ProtocolBlock.from(0xF2),
        };
        assertTrue(blocks[0].describe().startsWith("I-block"));
        assertTrue(blocks[1].describe().startsWith("R-block"));
        assertTrue(blocks[2].describe().startsWith("S-block"));

        assertEquals(0, ProtocolBlock.from(0x02).pcb() & 0x01);
        assertEquals(1, ProtocolBlock.from(0x03).pcb() & 0x01);
    }
}
```

Look closely at `polymorphicDescribe`: the array's type is `ProtocolBlock[]`. The code calling `describe()` has **no idea** which subclass each element is, and doesn't need to. That's the entire point.

> `instanceof` in the first test is fine — a test's job is to verify the factory picks correctly. But `instanceof` in *production* code is usually a sign you should have added a method to the base instead.

```bash
mvn test -Dtest=ProtocolBlockTest
```

---

## A note on inheritance, since it's the most overused tool in OOP

You've now used both of the two ways one class can relate to another:

- **Composition (HAS-A)** — `Rc522Reader` *has a* `Rc522Driver`. `Rc522Driver` *has a* `SpiLink`.
- **Inheritance (IS-A)** — `Rc522Reader` *is a* `Iso14443Reader`. `InformationBlock` *is a* `ProtocolBlock`.

The test is whether "IS-A" is literally true. An I-block *is a* protocol block — yes. Is a reader *a kind of* driver? No: a reader *uses* a driver. Different things.

> ⚠️ **This one bites hard.** Writing `class Rc522Driver extends Pi4jSpiLink` compiles fine and looks like a shortcut ("now the driver can call `transfer` directly!"). Then your unit test constructs a driver, which constructs a Pi4J context, which looks for a GPIO chip that doesn't exist on your laptop, and the test crashes with `No gpio chip found`. You've welded the driver to the hardware and lost the seam you built in Chapter 1.
>
> **Default to composition.** Reach for inheritance only when you genuinely have a family sharing an algorithm — like these blocks, or the reader base.

---

## Commit

```bash
git add -A
git commit -m "M4: ProtocolBlock hierarchy -- I/R/S blocks with a factory"
```

---

*Next: [05 — Activation](05-activation.md)*
