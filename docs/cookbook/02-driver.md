# 02 — The RC522 driver

**Goal:** turn "shift bytes over SPI" into "read and write named registers." End the chapter with a real chip reporting `version() == 0x92`.

**OOP concept:** encapsulation, and composition (HAS-A).

---

## The problem

Reading one RC522 register over raw SPI looks like this:

```java
byte[] tx = { (byte) (((0x37 << 1) & 0x7E) | 0x80), 0x00 };
byte[] rx = new byte[2];
link.transfer(tx, rx, 2);
int version = rx[1] & 0xFF;
```

Five lines, three magic numbers, and one bit-twiddle you have to get exactly right. Now imagine that at every one of the ~40 register accesses in a card transaction. That's ~200 lines of identical incantation, each an opportunity to type `0x7F` instead of `0x7E`.

## The idea: gather the mess in one place

That's all a class is, at its most useful: **a place to put the mess so the rest of the program doesn't have to know about it.** After this chapter, the rest of your program says:

```java
int version = driver.version();
driver.antennaOn();
```

and the address encoding exists in exactly one place, written once, correct once.

> **Analogy.** A car's steering wheel is an interface to a mess: a rack, a pinion, hydraulic assist, tie rods, geometry that changes with speed. You turn a wheel. You are not permitted to reach into the steering column mid-corner and adjust the rack — not because it's secret, but because the mechanism only works if it's driven through the one sanctioned control.
>
> `private` is that housing. It's not about hiding secrets; it's about **guaranteeing that the only way to affect this thing is through the paths you've thought about.**

### The address encoding, explained

From the MFRC522 datasheet, the first SPI byte is an address byte:

```
bit 7      : 1 = read, 0 = write
bits 6..1  : the 6-bit register address
bit 0      : always 0
```

So a register number has to be shifted left one place to land in bits 6..1:

```java
((register << 1) & 0x7E) | 0x80    // read
 (register << 1) & 0x7E            // write
```

`& 0x7E` is `0111 1110` — it masks off anything that overflowed past bit 6 and forces bit 0 to zero. `| 0x80` sets the read flag. That's the whole protocol, and after this chapter you never write it again.

---

## Build it

`src/main/java/com/midnightbrewer/hardware/Rc522Driver.java`:

```java
package com.midnightbrewer.hardware;

/**
 * The RC522 register-level driver.
 *
 * Encapsulation earns its keep here: the address encoding, the read/write bit
 * and the two-byte SPI dance all live IN HERE. The rest of the program calls
 * readRegister(0x37) and never writes ((reg << 1) & 0x7E) | 0x80 again.
 */
public class Rc522Driver {

    // ── register addresses (MFRC522 datasheet §9) ────────────────────
    private static final int COMMAND_REG    = 0x01;  // CommandReg
    private static final int TX_CONTROL_REG = 0x14;  // TxControlReg (antenna drivers)
    private static final int VERSION_REG    = 0x37;  // VersionReg (0x91 = v1, 0x92 = v2)

    private static final int PCD_SOFT_RESET = 0x0F;  // the SoftReset command

    // ── composition: the driver HAS-A SpiLink ────────────────────────
    //
    // 'private' = nobody outside can reach past the driver and touch the bus.
    // 'final'   = once set in the constructor, this never points elsewhere.
    // The type is the INTERFACE, so the driver cannot tell real from fake.
    private final SpiLink link;

    // Dependency injection: the bus is handed in, not constructed here.
    public Rc522Driver(SpiLink link) {
        this.link = link;
    }

    // ═════════════════════════════════════════════════════════════════
    // readRegister
    //
    //   C:  uchar Read_MFRC522(uchar addr) {
    //           SPI_Transfer( ((addr<<1)&0x7E) | 0x80 );  // address, read bit
    //           val = SPI_Transfer(0x00);                 // 2nd byte = answer
    //           return val;
    //       }
    //
    //   In Java ONE transfer() moves both bytes: build a 2-byte tx (encoded
    //   address, then 0x00), a 2-byte rx, call link.transfer, answer in rx[1].
    //   Return it masked with & 0xFF so 0x92 comes back as 146, not -110.
    // ═════════════════════════════════════════════════════════════════
    public int readRegister(int register) throws SpiException {
        // TODO
        return 0;
    }

    // ═════════════════════════════════════════════════════════════════
    // writeRegister
    //
    //   Same shape, two differences: the address byte does NOT set 0x80
    //   (that is what makes it a write), and the second byte is the value
    //   instead of 0x00. Nothing to read back.
    // ═════════════════════════════════════════════════════════════════
    public void writeRegister(int register, int value) throws SpiException {
        // TODO
    }

    // ═════════════════════════════════════════════════════════════════
    // setBitMask -- turn bits ON without disturbing the others.
    //
    //   C:  tmp = Read(reg);  Write(reg, tmp | mask);
    //
    //   "Read-modify-write". Build it from the two methods you just wrote --
    //   NOT from link.transfer. Each layer uses only the layer below it.
    // ═════════════════════════════════════════════════════════════════
    public void setBitMask(int register, int mask) throws SpiException {
        // TODO
    }

    /** Turn bits OFF: same shape, but AND with ~mask. */
    public void clearBitMask(int register, int mask) throws SpiException {
        // TODO
    }

    /** Write the SoftReset command to CommandReg. One line. */
    public void softReset() throws SpiException {
        // TODO
    }

    // ═════════════════════════════════════════════════════════════════
    // antennaOn -- power up the RF field.
    //
    //   C:  Read_MFRC522(TxControlReg);      // the C reads and ignores it
    //       SetBitMask(TxControlReg, 0x03);  // Tx1RFEn | Tx2RFEn
    //
    //   The two low bits of TxControlReg are the antenna drivers. Watch the
    //   register go 0x80 -> 0x83; that is read-modify-write doing its job.
    // ═════════════════════════════════════════════════════════════════
    public void antennaOn() throws SpiException {
        // TODO
    }

    /** The chip's version byte: 0x91 (v1.0) or 0x92 (v2.0). */
    public int version() throws SpiException {
        // TODO
        return 0;
    }
}
```

### Why `setBitMask` calls `readRegister`, not `link.transfer`

It would work either way. But **each layer should use only the layer directly beneath it.** `setBitMask` is built from `readRegister`/`writeRegister`; those are built from `link.transfer`; that's built from Pi4J. If you shortcut a layer, you've created a second path to the same hardware — and the day you need to add logging, retries, or a mutex to register access, one path gets it and the other silently doesn't.

> That's not theoretical. It's exactly how "I fixed the bug but it still happens sometimes" bugs are born.

---

## Verify — with no hardware

```java
package com.midnightbrewer.hardware;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Rc522DriverTest {

    @Test
    void readsTheVersionRegister() throws SpiException {
        FakeSpiLink link = new FakeSpiLink();
        link.setRegister(0x37, 0x92);

        Rc522Driver driver = new Rc522Driver(link);

        assertEquals(0x92, driver.version());
    }

    @Test
    void writeEncodesTheAddressWithoutTheReadBit() throws SpiException {
        FakeSpiLink link = new FakeSpiLink();
        Rc522Driver driver = new Rc522Driver(link);

        driver.writeRegister(0x14, 0x03);

        byte[] tx = link.sent.get(0);
        assertEquals((0x14 << 1) & 0x7E, tx[0] & 0xFF);   // read bit CLEAR
        assertEquals(0x03, tx[1] & 0xFF);
    }
}
```

That second test is the interesting one: it asserts on **what went out on the wire**, which is the only way to catch an encoding bug. A test that only checks return values would pass with the read bit set on a write.

```bash
mvn test -Dtest=Rc522DriverTest
```

---

## Now the real chip

`src/main/java/com/midnightbrewer/hardware/HardwareCheck.java` — a plain `main` that runs on the Pi:

```java
package com.midnightbrewer.hardware;

public final class HardwareCheck {

    private HardwareCheck() { }

    public static void main(String[] args) throws Exception {
        System.out.println("Opening the reader...");

        try (Pi4jSpiLink link = new Pi4jSpiLink()) {
            Rc522Driver driver = new Rc522Driver(link);

            driver.softReset();
            Thread.sleep(50);              // the chip needs a moment after reset
            driver.antennaOn();

            int version = driver.version();
            System.out.printf("version() = 0x%02X%n", version);
            if (version == 0x91 || version == 0x92) {
                System.out.println("  -> the driver works on silicon.");
            } else {
                System.out.println("  -> chip not answering. Check wiring/power.");
            }
        }
    }
}
```

Deploy and run:

```bash
# on your laptop
scp -r src pom.xml pi@raspberrypi.local:~/midnight-brewer/

# on the Pi
cd ~/midnight-brewer
mvn -q -DskipTests compile dependency:copy-dependencies -DincludeScope=runtime
java -cp "target/classes:target/dependency/*" com.midnightbrewer.hardware.HardwareCheck
```

Expected:

```
version() = 0x92
  -> the driver works on silicon.
```

### If you get `0x00` or `0xFF`

Those are the two "nobody home" values, and they're diagnostic:

- **`0x00`** — MISO is stuck low. Usually: MISO not connected, chip unpowered, or CS on the wrong pin.
- **`0xFF`** — MISO stuck high, i.e. floating. Usually: MISO connected to nothing, or the chip is dead.

Check power first (3.3 V, **not** 5 V), then wiring, then shorten your wires. Do not debug the code — the code is ten lines and your test already proved it.

> **`try (Pi4jSpiLink link = ...)`** is try-with-resources. It calls `close()` when the block exits — normally, by exception, or by early return. This is why `SpiLink extends AutoCloseable`. On a kiosk it means a crash can never leave the RF field energised, which is the sort of thing that quietly cooks a reader module overnight.

---

## Traps

| Symptom | Cause |
|---------|-------|
| `version()` = `-110` | Missing `& 0xFF`. |
| `version()` = `0x00` | MISO low: power, wiring, or wrong CS pin. |
| `version()` = `0xFF` | MISO floating: not connected. |
| Reads work, writes don't | Read bit `0x80` accidentally set on writes. |
| Works on the bench, flaky mounted | Wire length. Shorten them. |
| `0x92` in the test, `0x00` on the Pi | Stale `target/`. Run `mvn clean compile`. |

---

## Commit

```bash
git add -A
git commit -m "M2: Rc522Driver -- register read/write, bitmasks, reset, antenna"
```

---

*Next: [03 — The reader base class](03-reader-template.md)*
