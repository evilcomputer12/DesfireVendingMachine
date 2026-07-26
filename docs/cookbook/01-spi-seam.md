# 01 — The SPI seam

**Goal:** define what "an SPI bus" means to your program, build a fake one, and prove you can test against it with no hardware attached.

**OOP concept:** `interface`, and dependency injection.

---

## The problem

You're about to write a driver that pokes RC522 registers. Ask an uncomfortable question first:

> How will I know the driver is correct?

The obvious answer is "plug in the Pi and see if the LED blinks." That answer will cost you the entire project. Here's the loop it commits you to: edit code on the laptop → copy to the Pi → compile on a Zero 2 W → run → squint at output → guess. Two minutes per iteration, and every failure has three possible causes (your logic, your wiring, the chip) with no way to tell them apart.

You want a loop that takes 200 ms and has exactly *one* possible cause: your logic.

## The idea: program against a description, not a thing

Your driver doesn't actually need "a Raspberry Pi SPI bus." Look at what it truly needs: *something that takes bytes out and hands bytes back*. That's the whole requirement. A real Pi satisfies it. So does a lookup table you write in ten lines.

So instead of the driver depending on a concrete Pi4J class, you write down that *requirement* as a type of its own — an **interface** — and let the driver depend on the requirement. Then you can satisfy the requirement two ways: with real hardware, or with a fake.

> **Analogy.** A wall socket is an interface. Your kettle doesn't depend on "the Hinkley Point C nuclear reactor"; it depends on "230 V at 50 Hz through two holes shaped like this." That's why the kettle also works on a generator, a solar inverter, or a test rig in the factory. The socket standard is a *contract*: it says nothing about how the electricity was made, only what shows up at the holes. Every appliance ever built gets to work with every power source ever built, because both sides code against the socket rather than against each other.
>
> An interface is a socket. `SpiLink` is the socket shape; `Pi4jSpiLink` is the power station; `FakeSpiLink` is the bench supply you use to test the kettle without burning coal.

In C you'd get halfway there with a struct of function pointers, and the idea is genuinely the same. Java's version is checked by the compiler: a class that claims to satisfy the contract but forgets a method doesn't compile.

---

## Build it

### `SpiException` — the failure channel

Before the interface, the failure mode. SPI can fail: the device file might not exist, a transfer can error. In C you'd return `-1` and hope the caller checks. Java gives you a better tool.

`src/main/java/com/midnightbrewer/hardware/SpiException.java`:

```java
package com.midnightbrewer.hardware;

/**
 * Something went wrong talking to the SPI bus.
 *
 * Deliberately a CHECKED exception (extends Exception, not RuntimeException):
 * the compiler forces every caller to either handle it or declare that it
 * throws too. Hardware failure is not an edge case you get to forget about,
 * so we make the compiler nag.
 */
public class SpiException extends Exception {

    public SpiException(String message) {
        super(message);
    }

    public SpiException(String message, Throwable cause) {
        super(message, cause);   // keeps the original error's stack trace
    }
}
```

**Why checked?** Java has two flavours of exception. *Unchecked* ones (`RuntimeException`) can be ignored silently. *Checked* ones must be acknowledged — a caller either catches it or adds `throws SpiException` to its own signature.

That distinction is a design statement. "The bus failed" is not something you should be able to forget, so we make forgetting a compile error. The cost is that `throws SpiException` propagates up through your call stack, which is a little noisy — but that noise is an accurate map of *everything that can fail because hardware exists*, and that map turns out to be useful.

**Why the second constructor?** `super(message, cause)` preserves the underlying error. When Pi4J throws some `IOException` deep inside, you wrap it with your own context ("SPI transfer failed") while keeping the original stack trace. Debug output becomes "my error, *caused by* their error" instead of one or the other.

### `SpiLink` — the contract

`src/main/java/com/midnightbrewer/hardware/SpiLink.java`:

```java
package com.midnightbrewer.hardware;

/**
 * The seam between this program and an SPI bus.
 *
 * An interface is a PROMISE, not code: any class that says
 * "implements SpiLink" must supply these methods, and the compiler enforces it.
 *
 * Everything above this line (the driver, the reader, the whole card stack)
 * depends only on this promise -- never on Pi4J, never on a device file. That
 * is what lets a test swap in a fake and the kiosk swap in real silicon,
 * with zero changes to anything in between.
 *
 * Extends AutoCloseable so callers can use try-with-resources and the RF field
 * is never left energised by a crash.
 */
public interface SpiLink extends AutoCloseable {

    /**
     * Full-duplex SPI transfer: shift out txBuffer, shift in rxBuffer.
     *
     * SPI is inherently full duplex -- every clock pulse sends a bit AND
     * receives a bit. So this is ONE method, not a send() and a receive().
     * The API mirrors the hardware rather than pretending it's a stream.
     *
     * @param txBuffer bytes to send
     * @param rxBuffer filled with the bytes received (same length)
     * @param length   how many bytes to move
     */
    void transfer(byte[] txBuffer, byte[] rxBuffer, int length) throws SpiException;

    /** Release the bus. Overridden from AutoCloseable to narrow the exception. */
    @Override
    void close() throws SpiException;
}
```

Notice there is **no code here at all** — no fields, no bodies. An interface is pure vocabulary. That's exactly why it makes a good seam: there's nothing in it that could depend on a Pi.

> **The signature is a design decision.** `transfer(tx, rx, length)` mirrors SPI's full-duplex reality: one call, both directions. Had I written `send()` and `receive()` separately, every caller would have to know that a "read" is really *write the address, then clock out a dummy byte to shift the answer in* — a hardware detail leaking into every call site. Design the interface around what the hardware genuinely is, and the code above it stays honest.

### `FakeSpiLink` — the test double

`src/test/java/com/midnightbrewer/hardware/FakeSpiLink.java` — note this lives in **test**, so it never ships.

```java
package com.midnightbrewer.hardware;

import java.util.ArrayList;
import java.util.List;

/**
 * A fake SPI bus for tests: it answers from a canned table and records
 * everything it was asked to send.
 *
 * This is the payoff of the interface. The driver cannot tell this from a real
 * Pi -- it only ever saw the SpiLink promise, and this keeps that promise.
 */
class FakeSpiLink implements SpiLink {

    /** Register address -> value the fake chip should report. */
    private final java.util.Map<Integer, Integer> registers = new java.util.HashMap<>();

    /** Every tx buffer we were handed, in order, so a test can assert on it. */
    final List<byte[]> sent = new ArrayList<>();

    private boolean closed = false;

    /** Pre-load what the fake chip will answer for a register. */
    void setRegister(int address, int value) {
        registers.put(address, value);
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void transfer(byte[] txBuffer, byte[] rxBuffer, int length) {
        sent.add(txBuffer.clone());

        // TODO 1: decode the address byte the driver sent.
        //   The driver encodes a READ as ((addr << 1) & 0x7E) | 0x80.
        //   So to get the address back:  (txBuffer[0] & 0x7E) >> 1
        //   and it is a read if (txBuffer[0] & 0x80) != 0.

        // TODO 2: if it was a read, put the canned value in rxBuffer[1]
        //   (the answer arrives on the SECOND byte -- the first byte out is
        //   the address, and while that goes out the chip has nothing to say
        //   yet). Default to 0 for an address nobody configured.

        // TODO 3: if it was a write, there is nothing to answer. Leave rx alone.
    }

    @Override
    public void close() {
        closed = true;
    }
}
```

> ⚠️ **The trap that catches everyone: a fake that returns nothing.** If you leave `transfer()` empty, `rxBuffer` stays all zeros, your driver dutifully returns `0`, and your test asserting `version() == 0x92` fails — so you go hunting for a bug in the driver that isn't there. Worse is the opposite: a test that passes because *both* sides are trivially empty.
>
> **A fake must be a plausible liar, not a mute.** It has to behave enough like the real thing that the code under test is genuinely exercised.

### The test

`src/test/java/com/midnightbrewer/hardware/FakeSpiLinkTest.java`:

```java
package com.midnightbrewer.hardware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeSpiLinkTest {

    @Test
    void readReturnsTheCannedValue() throws SpiException {
        FakeSpiLink link = new FakeSpiLink();
        link.setRegister(0x37, 0x92);          // VersionReg answers 0x92

        // Hand-encode a READ of register 0x37, exactly as the driver will.
        byte[] tx = { (byte) (((0x37 << 1) & 0x7E) | 0x80), 0x00 };
        byte[] rx = new byte[2];
        link.transfer(tx, rx, 2);

        assertEquals(0x92, rx[1] & 0xFF);      // & 0xFF -- the signed-byte thing
        assertEquals(1, link.sent.size());
    }

    @Test
    void closeIsRecorded() throws Exception {
        FakeSpiLink link = new FakeSpiLink();
        assertTrue(!link.isClosed());
        link.close();
        assertTrue(link.isClosed());
    }
}
```

```bash
mvn test -Dtest=FakeSpiLinkTest
```

### `Pi4jSpiLink` — the real one

`src/main/java/com/midnightbrewer/hardware/Pi4jSpiLink.java`:

```java
package com.midnightbrewer.hardware;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiMode;

/**
 * The real SPI bus, via Pi4J and /dev/spidev0.0.
 *
 * The ONLY class in the project that knows Pi4J exists. Everything else sees
 * the SpiLink interface. Contain your dependencies in one place and swapping
 * them later is a one-file job.
 */
public class Pi4jSpiLink implements SpiLink {

    private static final int SPI_CHANNEL = 0;        // CE0
    private static final int SPI_BAUD    = 1_000_000; // 1 MHz -- reliable on jumpers

    private final Context pi4j;
    private final Spi spi;

    public Pi4jSpiLink() {
        this.pi4j = Pi4J.newAutoContext();
        this.spi = pi4j.create(Spi.newConfigBuilder(pi4j)
                .id("rc522")
                .address(SPI_CHANNEL)
                .baud(SPI_BAUD)
                .mode(SpiMode.MODE_0)   // RC522 is mode 0: CPOL=0, CPHA=0
                .build());
    }

    @Override
    public void transfer(byte[] txBuffer, byte[] rxBuffer, int length) throws SpiException {
        try {
            spi.transfer(txBuffer, 0, rxBuffer, 0, length);
        } catch (RuntimeException e) {
            throw new SpiException("SPI transfer failed", e);
        }
    }

    @Override
    public void close() throws SpiException {
        try {
            spi.close();
            pi4j.shutdown();
        } catch (RuntimeException e) {
            throw new SpiException("failed to close SPI", e);
        }
    }
}
```

**Why 1 MHz?** The MFRC522 handles 10 MHz on a clean PCB. On loose jumper wires it does not. 1 MHz is fast enough that SPI is never your bottleneck (the card's RF timing dominates by orders of magnitude) and slow enough to tolerate bad wiring. Optimise the thing that's actually slow, not the thing that's easy to measure.

**Why mode 0?** CPOL=0, CPHA=0 — from the MFRC522 datasheet. Clock idles low, data sampled on the rising edge. Get this wrong and you read plausible-looking garbage, which is the worst kind.

---

## The concept, now that you've used it

**Dependency injection** is the fancy name for something you just did: `Rc522Driver` will receive its `SpiLink` from outside, through its constructor, rather than constructing one itself.

```java
// If the driver did this, it would be welded to the Pi forever:
class Rc522Driver {
    private final SpiLink link = new Pi4jSpiLink();   // ← untestable
}

// Handing it in from outside means the caller decides:
class Rc522Driver {
    private final SpiLink link;
    Rc522Driver(SpiLink link) { this.link = link; }   // ← testable
}
```

Same code, two power sources. The kettle doesn't own the power station.

> **A rule worth internalising:** a class should not construct the things it depends on. It should be *handed* them. "Who decides?" moves up to whoever assembles the program — and testability, flexibility, and clear ownership all follow from that one move.

---

## Traps

| Symptom | Cause |
|---------|-------|
| `version()` returns `-110` | Missing `& 0xFF`. `0x92` as a signed byte is −110. |
| Fake test passes but hardware returns 0 | Fake returns canned data unconditionally; check it decodes the address. |
| `NoClassDefFoundError: com/sun/jna/...` | JNA missing on the classpath. Add it, or use `dependency:copy-dependencies`. |
| Works once, fails after | You didn't `close()`. The device file stays locked. Use try-with-resources. |

---

## Commit

```bash
git add -A
git commit -m "M1: SpiLink interface + fake and Pi4J implementations"
```

---

*Next: [02 — The RC522 driver](02-driver.md)*
