# Learning OOP by building the RC522 driver in Java

You already have a working RC522 stack in C. That is the ideal starting
position for learning object-oriented design, because you are not fighting two
problems at once. The protocol questions are answered — you know what bytes go
out and what comes back. Everything left is a *design* question, which is
exactly the thing the textbook is trying to teach and cannot demonstrate with
`Animal extends Shape`.

## How this works

**You write the logic. I scaffold, review, and ask awkward questions.**

Each milestone below gives you a concept, a task, the design questions worth
sitting with, and a "done when" you can check yourself. It does not give you
the implementation. When you have written one, ask me to review it — that is
where most of the learning actually happens, because I can point at a specific
line and say "this is why that field wants to be private".

If you get stuck for more than about twenty minutes on *mechanics* (Pi4J API,
Java syntax, Maven), just ask. Don't burn the evening on incidental friction.
Get stuck on *design* as long as you like; that part is the point.

---

## M0 — Toolchain proven ✅

`Rc522Probe` runs on the Pi. Pi4J loads its native libraries, `gpiod` claims
BCM 25, `linuxfs` opens `/dev/spidev0.0`, and SPI transfers complete. With
nothing wired it reads `0x00`; wired correctly it will read `0x91` or `0x92`.

**Read `Rc522Probe` now, and notice what is wrong with it.** One flat `main`.
Magic numbers. No error types. No way to run any of it without the board
plugged in. It is a faithful Java transcription of procedural C — it works,
and you cannot take it apart. Every milestone below dismantles one part of it.

---

## M1 — Interfaces: the hardware seam

**Concept:** interfaces, dependency inversion, test doubles.

**Task.** Define your own abstraction for "talk to a chip over SPI", with two
implementations: one backed by Pi4J, one fake.

**Design questions — answer these before writing:**

1. What is the right *level* for this abstraction? You already wrote
   `HardwareBus` with `readRegister`/`writeRegister`. But SPI does not have
   registers — SPI has *transfers*. Registers are an RC522 concept. So does
   your interface belong at `transfer(byte[] tx, byte[] rx)`, or at
   `readRegister(addr)`? Which one could you reuse for a different SPI chip?
   Which one hides more? There is a real trade-off here; pick a side and be
   able to say why.

2. Your fake implementation has to answer `0x91` when asked for `VersionReg`.
   How does a test tell it what to answer? How does a test then *assert* which
   bytes were sent? Notice that designing for the fake is what forces the
   interface to be small.

3. **Naming trap:** Pi4J already has `com.pi4j.io.spi.SpiBus`. If you name
   yours `SpiBus` too, every file importing both gets ugly fast. Pick another
   name.

**Done when:** you have a unit test that reads a version register and asserts
the result, running on your Mac, with no Pi and no RC522 anywhere near it.

That test is the payoff. Write it and you will understand what interfaces are
*for* in a way no textbook example can teach — you got the ability to test
hardware code without hardware, purely by putting a seam in the right place.

---

## M2 — Encapsulation: the driver

**Concept:** encapsulation, class invariants, why `private` is a design tool
rather than a rule.

**Task.** An `Rc522Driver` that owns a bus and exposes register operations.

Move the bit-twiddling out of sight. Callers should never write
`((reg << 1) & 0x7E) | 0x80` again — that encoding is the RC522's business,
not its callers'.

**Design questions:**

1. `setBitMask` and `clearBitMask` in your C are read-modify-write. What
   happens if two threads call them at once? Your kiosk *does* have more than
   one thread — the JavaFX thread and the card-polling thread. Is that a real
   risk here, and if so, what is the cheapest correct fix?

2. Should `Rc522Driver` create its own bus, or be handed one? Try writing the
   constructor both ways and see which one you can test.

3. The chip has state: it is reset, or initialised, or mid-transceive. Can a
   caller currently call your methods in a nonsensical order? Should they be
   able to?

**Done when:** `Rc522Probe`'s job can be done by `new Rc522Driver(bus).version()`,
and the driver has tests using your fake bus.

---

## M3 — Abstract classes: card activation

**Concept:** abstract classes, the Template Method pattern, and the actual
difference between "abstract class" and "interface" — which almost every
tutorial explains badly.

**Task.** The activation sequence: REQA/WUPA → anticollision → SELECT →
(cascade level 2 for 7-byte UIDs) → RATS.

Notice the shape of this: the *sequence* is fixed and the *steps* vary. That
is precisely what Template Method is for — a base class that owns the
algorithm and defers individual steps to subclasses.

**Design questions:**

1. Interfaces cannot hold state or shared code; abstract classes can, but a
   class may only extend one. Given that, which fits here — and why is
   `Iso14443Transceiver` (already in the repo) an interface while this wants
   to be an abstract class?

2. DESFire has a 7-byte UID, so `SAK & 0x04` is set and you must run a second
   cascade level. Is that a subclass, a branch, or a separate collaborator?
   Argue it either way.

3. If the template method is `public final activate()` calling `protected
   abstract` steps — why `final`? What breaks if a subclass overrides the
   sequence itself?

**Done when:** you can activate a card and print its UID, and the cascade-level
logic is expressed once rather than copy-pasted.

---

## M4 — Polymorphism: ISO-DEP blocks

**This is the one.** If you do only one milestone properly, do this one.

**Concept:** polymorphism replacing conditionals; the Open/Closed Principle;
sealed types.

Go and look at how your C handles I-blocks, R-blocks and S-blocks. It will be
`switch` statements, or `if (pcb & 0x...)` chains, and the *same* dispatch
logic will appear in several places — one to build, one to parse, one to
decide how to reply.

**Task.** Model the three block types as actual types. Each knows how to
encode itself, how to interpret a response, and what should happen next.

**Design questions:**

1. Every `switch` on a type code is polymorphism waiting to happen. If you add
   a fourth block type, how many places change in the C version? How many in
   yours? That number is the entire argument for this milestone.

2. Java 17 has `sealed interface`. Should this hierarchy be sealed? What do
   you gain — and what does the compiler then do for you in a `switch`?

3. Chaining: a long APDU splits across multiple I-blocks with the chaining bit
   set. Who owns that loop — the block, the transceiver, or something else?
   There is a defensible answer other than "the transceiver"; find it.

4. Where does the block *number* toggle live? It is per-session state, not
   per-block. What does that tell you about what should be a field and what
   should be a parameter?

**Done when:** `Rc522SoftwareIsoDep implements Iso14443Transceiver` works
against your fake bus with a canned exchange, and there is no `switch` on
block type in your transceive loop.

---

## M5 — Composition and exceptions

**Concept:** composition over inheritance; exception hierarchies as design.

Wire the stack together: `DesfirePaymentTerminal` *has an*
`Iso14443Transceiver`, which *has an* `Rc522Driver`, which *has a* bus. Note
that nothing here inherits from anything — a terminal is not a kind of SPI
bus. Composition is the default; inheritance is the exception.

The exception hierarchy in `com.midnightbrewer.card` already exists. Now make
it earn its place: which of your C's `-1` returns becomes which exception, and
which should not be an exception at all? ("No card in the field" is the normal
case, not an error. Does it deserve a throw?)

**Done when:** the kiosk runs against real hardware by changing one line in
`UIController#createTerminal()`, and nothing else.

---

## The thread that runs through all of it

Every milestone is really the same question in a different costume:

> What does this piece need to know, and what should it be prevented from
> knowing?

The kiosk must not know about SPI. The transceiver must not know what a
"balance" is. The block types must not know which chip is sending them. When
you get that boundary right the code becomes testable almost by accident —
which is why "can I test this without hardware?" is the fastest proxy for "is
this design any good?"

---

## Order of work

1. Wire the RC522 (see the pin table in the chat, and mind the 3.3V rail).
2. Run `Rc522Probe`. Get `0x91`/`0x92`. **Do not proceed until you do** —
   otherwise you will be debugging a design and a loose wire simultaneously.
3. M1. Bring me the interface and I will try to poke holes in it before you
   build on top of it.
4. M2 → M5, reviewing as you go.

Start with M1 as soon as the probe answers. And when you have written it, ask
me to review — reading someone pull your own design apart is worth more than
another chapter of the book.
