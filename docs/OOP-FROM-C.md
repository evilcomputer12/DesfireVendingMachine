# OOP for a C programmer, using your own code

You know pointers, structs, function pointers and bit manipulation. You do not
need OOP explained with animals and shapes. You need to know what problem each
OOP feature solves that plain structs and functions did not — because you have
already hit those problems and solved several of them by hand.

We will use code from *this repository* throughout.

---

## Part 0 — You already wrote an interface

Open `lib/desfire/desfire_cmd.h` and look at what you wrote:

```c
typedef DFStatus (*df_transceive_fn)(void *user, const uint8_t *send,
                                     uint8_t send_len, uint8_t *resp,
                                     uint8_t *resp_len);

struct DFContext {
    df_transceive_fn  transceive;   /* a function pointer */
    void             *user;         /* context for that function */
    df_random_fn      random;
    void             *random_user;
    df_delay_fn       delay;
    void             *delay_user;
    ...
};
```

And in `desfiire.c` you plug a concrete function into it:

```c
df_ctx_init_full(&app->ctx,
                 platform_send_apdu,   /* <- the implementation */
                 app,                  /* <- its context */
                 ...);
```

Look at what you achieved. `desfire_cmd.c` sends APDUs by calling
`ctx->transceive(...)`. It has **no idea** whether that lands on an RC522, a
PN532, or a test harness. You could swap `platform_send_apdu` for
`replay_from_logfile` and the entire DESFire layer would neither notice nor
care.

**That is an interface.** You invented it by hand because you needed it.

What Java gives you is the same idea with three things you had to do yourself:

| You did by hand | Java does |
|---|---|
| `void *user` to carry context | An object carries its own fields |
| Trusting the right function is assigned | The compiler checks it |
| Grouping related pointers in a struct | `interface` groups them by name |

That last row matters. Nothing stops a C caller from assigning a wrong-shaped
cast, or leaving `transceive` NULL and crashing at runtime. In Java that is a
compile error.

So you are not learning a new idea. You are learning syntax for an idea you
already reached on your own.

---

## Part 1 — A class is a struct plus the functions that operate on it

In C, data and the code that works on it live apart:

```c
/* the data */
struct Rc522 { SPI_HandleTypeDef *spi; };

/* the functions — every one takes the data as its first argument */
uint8_t Read_MFRC522(struct Rc522 *dev, uint8_t reg);
void    Write_MFRC522(struct Rc522 *dev, uint8_t reg, uint8_t val);
```

In Java they live together, and the "first argument" becomes implicit:

```java
public class Rc522Driver {

    private final SpiLink link;          // the struct fields

    public Rc522Driver(SpiLink link) {   // the constructor
        this.link = link;
    }

    public int readRegister(int register) {   // was Read_MFRC522(dev, reg)
        ...
    }
}
```

Reading it keyword by keyword:

- **`public`** — other code may use this. `private` means only this class may.
- **`class Rc522Driver`** — declares both a type and the code that works on it.
- **`private final SpiLink link;`** — a field, like a struct member.
  `private` = nobody outside this class can touch it. `final` = assigned once,
  never reassigned (like `SpiLink *const link`).
- **The constructor** — the function with the class's name and no return type.
  It runs when you write `new Rc522Driver(...)`. It is your `Rc522_Init()`,
  except the language guarantees it runs before anyone can use the object.
- **`this.link = link;`** — `this` is the implicit `dev` pointer. `this.link`
  is the field; plain `link` is the parameter. Same name, disambiguated.

Calling it:

```c
/* C */
struct Rc522 dev;
Rc522_Init(&dev, &hspi1);
uint8_t v = Read_MFRC522(&dev, VersionReg);
```

```java
// Java
Rc522Driver dev = new Rc522Driver(link);
int v = dev.readRegister(VERSION_REG);
```

`dev.readRegister(...)` is `Read_MFRC522(&dev, ...)`. That is the entire
difference. The object is passed for you, and it cannot be null or the wrong
type.

---

## Part 2 — Encapsulation is a compiler-enforced boundary

In C, `private` is a comment. Anyone with the struct pointer can write any
field, and nothing stops them.

That is not hypothetical here. Look at `DFSession`:

```c
typedef struct {
    uint8_t  sessKeyEnc[16];
    uint8_t  sessKeyMac[16];
    uint8_t  ti[4];
    uint16_t cmdCounter;    /* <-- */
    ...
} DFSession;
```

If any code anywhere writes `session.cmdCounter = 0` at the wrong moment,
every CMAC afterwards silently fails to verify, and you get "integrity error"
from a card that is working perfectly. You would spend an evening on that.

In Java you make it structurally impossible:

```java
public class DesfireSession {
    private int commandCounter;         // nobody outside can touch it

    public void incrementCounter() {    // the ONLY way it changes
        commandCounter++;
    }
}
```

Now `session.commandCounter = 0` is a **compile error**. Not a code review
note, not a convention — the build fails.

That is what encapsulation buys: you decide which invariants matter, and the
compiler enforces them for you everywhere, forever.

> **Rule of thumb:** start every field `private`. Open it up only when
> something outside genuinely needs it, and prefer a method over exposing the
> field, so you keep control of *how* it changes.

---

## Part 3 — An interface is your function-pointer struct, type-checked

Here is your C pattern again, minimal:

```c
typedef struct {
    void (*transfer)(void *ctx, uint8_t *tx, uint8_t *rx, int len);
    void *ctx;
} SpiLink;
```

And the Java equivalent:

```java
public interface SpiLink {
    void transfer(byte[] tx, byte[] rx);
}
```

An **interface** is a list of function signatures with no bodies. It is a
promise: *"anything calling itself a SpiLink can do these things."*

A class then promises to keep that contract:

```java
public class Pi4jSpiLink implements SpiLink {
    @Override
    public void transfer(byte[] tx, byte[] rx) {
        spi.transfer(tx, rx, tx.length);     // real hardware
    }
}

public class FakeSpiLink implements SpiLink {
    @Override
    public void transfer(byte[] tx, byte[] rx) {
        rx[1] = (byte) 0x92;                 // canned answer, no hardware
    }
}
```

- **`implements SpiLink`** — "I keep this contract." If you forget a method,
  the code does not compile. Compare that with C, where a NULL function
  pointer compiles fine and crashes at 2am.
- **`@Override`** — tells the compiler "this is meant to fulfil the contract".
  If you typo `transfar`, it errors instead of silently adding a new method.
  Always write it.

Now the payoff. `Rc522Driver` takes a `SpiLink`:

```java
public Rc522Driver(SpiLink link) { this.link = link; }
```

and never learns which kind it got:

```java
new Rc522Driver(new Pi4jSpiLink(pi4j));   // on the Pi, real chip
new Rc522Driver(new FakeSpiLink());       // on your Mac, in a test
```

**Identical driver code. No hardware required to test it.** That is exactly
what `void *user` bought you in C, minus the casts and the crashes.

---

## Part 4 — Why "program to the interface" is the whole game

The habit is: depend on the *contract*, never on the *concrete thing*.

```java
private final SpiLink link;        // GOOD — any implementation fits
private final Pi4jSpiLink link;    // BAD  — now it only runs on a Pi
```

Both compile. The second one quietly makes the class untestable off the Pi
forever, and you will not notice until you try to write a test and cannot.

You have already seen this pay off in this repo. `UIController` depends on the
`PaymentTerminal` interface, so the kiosk runs today against
`SimulatedPaymentTerminal` with no card reader in existence. When you write
`DesfirePaymentTerminal`, one line changes.

---

## Part 5 — Now write M1

You are building the seam between "SPI wire" and "RC522 chip". Four small
files.

### File 1 — `src/main/java/com/midnightbrewer/hardware/SpiLink.java`

Here is the whole thing. Type it out rather than pasting; it is ten lines and
you want the syntax in your fingers.

```java
package com.midnightbrewer.hardware;

/**
 * Moves bytes over an SPI bus. Knows nothing about the RC522.
 */
public interface SpiLink extends AutoCloseable {

    /**
     * Full duplex: clocks tx out while clocking rx in.
     * Both arrays must be the same length.
     */
    void transfer(byte[] tx, byte[] rx);

    /** Releases the device. */
    @Override
    void close();
}
```

- **`package ...`** — the folder path, like a header include prefix.
- **`extends AutoCloseable`** — a built-in Java interface meaning "I hold a
  resource that must be released". It enables try-with-resources:
  ```java
  try (SpiLink link = new Pi4jSpiLink(pi4j)) {
      ...
  }   // close() runs automatically, even if an exception is thrown
  ```
  It is `free()` that you cannot forget.
- Notice there is **no** `readRegister` here. Registers are an MFRC522 idea,
  not an SPI idea. That belongs one layer up.

### File 2 — `src/main/java/com/midnightbrewer/hardware/Pi4jSpiLink.java`

`public class Pi4jSpiLink implements SpiLink`.

- A `private final Spi spi;` field.
- A constructor taking the Pi4J `Context`, building the `Spi` exactly as
  `Rc522Probe` does, storing it in the field.
- `transfer()` → one line delegating to `spi.transfer(tx, rx, tx.length)`.
- `close()` → close the `Spi`.

Copy the body out of `Rc522Probe`. This file is mechanical on purpose — the
thinking already happened in File 1.

### File 3 — `src/test/java/com/midnightbrewer/hardware/FakeSpiLink.java`

**Note the path: `src/test/java`.** Code there is compiled for tests only and
never ships.

`public class FakeSpiLink implements SpiLink`, holding:

```java
private final Map<Integer, Integer> registers = new HashMap<>();
```

A `Map` is a hash table: `registers.put(0x37, 0x92)` then
`registers.get(0x37)`. Give it a `setRegister(int reg, int value)` method for
tests to program it.

In `transfer()`: byte 0 of `tx` is the encoded address. Decode it back to a
register number, look it up, and put the answer in `rx[1]`.

Decoding is the inverse of what the probe does:

```java
int register = (tx[0] & 0x7E) >> 1;
```

### File 4 — `src/test/java/com/midnightbrewer/hardware/Rc522DriverTest.java`

```java
class Rc522DriverTest {

    @Test
    void readsTheVersionRegister() {
        FakeSpiLink fake = new FakeSpiLink();
        fake.setRegister(0x37, 0x92);

        // ...build your driver on the fake and assert it returns 0x92
    }
}
```

`@Test` marks a method the test runner should execute.

Run `mvn test`. Green, on your Mac, with no Pi and no RC522.

**That is M1.** The point is not the code — it is that you can now develop and
test the entire RC522 stack on a laptop, and only touch hardware when you
genuinely need to.

---

## Glossary

| Term | What it means | Your C equivalent |
|---|---|---|
| class | data + its functions | `struct` + functions taking `struct*` |
| object / instance | one live copy | one `struct` variable |
| field | a variable inside a class | struct member |
| method | a function inside a class | function taking `struct*` |
| constructor | runs on creation | your `_Init()` function |
| `this` | the current object | the `dev` / `self` pointer |
| `private` | only this class may touch it | (nothing — convention only) |
| `final` | assign once | `const` |
| interface | a contract of signatures | struct of function pointers |
| `implements` | promises to keep a contract | assigning those pointers |
| `@Override` | "this fulfils the contract" | (nothing) |
| `new` | allocate + construct | `malloc` + `_Init()` |
| `null` | reference to nothing | `NULL` |

No `free()`. The garbage collector reclaims objects nothing points at any
more. `close()` is only for OS resources like file handles and SPI devices.
