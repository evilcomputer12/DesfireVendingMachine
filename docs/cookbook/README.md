# The Midnight Brewer Cookbook

**Build a contactless payment terminal from zero: SPI bytes → RC522 driver → ISO-14443 → AES crypto → a card that pays for coffee.**

This book exists so you can rebuild this entire project from an empty folder, alone, without looking at the finished code. Every chapter explains *why* the code is shaped the way it is, not just what to type.

---

## Who this is for

You know C. You've blinked an LED, you've read a datasheet, you've written `uint8_t buf[16]`. You do **not** need to know Java, OOP, JavaFX, or cryptography — those get built up chapter by chapter, each one introduced at the moment a real problem demands it.

That last point is the whole teaching method: **no concept is introduced before the problem that requires it.** You won't read "an interface is a contract" as an abstract definition. You'll hit the concrete wall of *"I can't test my driver without a Raspberry Pi plugged in"*, and the interface will be the tool that gets you over it. Concepts learned that way stick, because they're attached to a memory of being stuck.

## What you'll have at the end

A wall-mounted coffee kiosk. Touch a drink, hold a DESFire card to the reader, and:

1. the RC522 wakes the card and reads its UID,
2. your code and the card prove to each other they hold the same AES-128 key — without either ever transmitting it,
3. your code reads the balance, encrypted and authenticated,
4. it debits the price, commits the transaction, and the drink is dispensed.

Plus an Android app that tops the card back up over the phone's NFC radio.

## Table of contents

### Part 0 — Groundwork
| # | Chapter | You end with |
|---|---------|--------------|
| 00 | [Setup: hardware, Pi, and an empty project](00-setup.md) | Wired reader, `mvn compile` passing on an empty skeleton |

### Part 1 — Talking to the chip
| # | Chapter | OOP concept | You end with |
|---|---------|-------------|--------------|
| 01 | [The SPI seam](01-spi-seam.md) | interface, dependency injection | A fake bus you can unit-test against |
| 02 | [The RC522 driver](02-driver.md) | encapsulation, composition | `version()` returning `0x92` from real silicon |

### Part 2 — Talking to the card
| # | Chapter | OOP concept | You end with |
|---|---------|-------------|--------------|
| 03 | [The reader base class](03-reader-template.md) | abstract class, Template Method | `isCardPresent()` detecting a real card |
| 04 | [Protocol blocks](04-blocks.md) | inheritance, polymorphism, factory | A 3-class block hierarchy with tests |
| 05 | [Activation](05-activation.md) | value objects, immutability | UID, SAK and ATS off a real card |
| 06 | [The APDU channel](06-apdu-channel.md) | composition, layering | A real DESFire answering `GetVersion` |

### Part 3 — Cryptography
| # | Chapter | OOP concept | You end with |
|---|---------|-------------|--------------|
| 07 | [AES-CMAC from scratch](07-cmac.md) | immutable objects, invariants | RFC 4493 vectors passing |
| 08 | [Session keys](08-session-keys.md) | private static helpers, enums | EV2 key derivation verified offline |
| 09 | [Mutual authentication](09-authenticate.md) | seams for randomness, DI | A real card proving it holds your key |
| 10 | [Secure messaging & the debit](10-secure-messaging.md) | growing a class, state machines | **Money moving off a real card** |

### Part 4 — Product
| # | Chapter | Concept | You end with |
|---|---------|---------|--------------|
| 11 | [The kiosk UI](11-kiosk.md) | Adapter pattern, threading | A touchscreen that takes payments |
| 12 | [The Android top-up app](12-android-topup.md) | porting a design across languages | A phone that adds credit |
| 13 | [Deploying to the Pi](13-deploy.md) | systemd, kiosk mode, GPU | It boots into the kiosk |

### Appendices
- [A — All test vectors in one place](A-test-vectors.md)
- [B — The bug museum: every trap and its symptom](B-troubleshooting.md)
- [C — YouTube build plan: episodes, scripts, shot list](C-video-plan.md)

---

## How to use this book

**Read the chapter, then close it and write the code.** Each chapter gives you:

1. **The problem** — what doesn't work yet, in concrete terms.
2. **The idea** — the design, with an analogy from outside programming.
3. **The boilerplate** — a skeleton with `TODO` comments. Type it in yourself; don't copy-paste. The class shape, fields, and constants are given, so you spend your thinking on the logic, not on remembering Java syntax.
4. **The verification gate** — a test with known-good vectors. *Do not move to the next chapter until this passes.* This rule is the single most important one in the book.
5. **The traps** — the specific bugs that will bite you, with their symptoms.

### Why the boilerplate has holes in it

Because reading code teaches you almost nothing and writing it teaches you almost everything. The skeletons hand you the parts that are pure lookup (register addresses, class declarations, import lists) and withhold the parts that are actual reasoning (the algorithm, the byte layout, the control flow).

If you get truly stuck, the finished implementation of every one of these files exists — it's the repo this book lives in. Treat it as the back of the textbook: consulting it costs you the exercise, so spend at least 20 minutes stuck first. Being stuck *is* the learning; the answer just ends it.

### The verification gate, and why it's non-negotiable

Cryptographic code is uniquely cruel: it either produces the exact right 16 bytes or it produces garbage, and garbage looks exactly like more crypto. There is no "mostly working." A single flipped bit in your key-derivation and every subsequent chapter fails for reasons that look like a hardware fault.

So every crypto chapter ends with **known-answer test vectors** — inputs with independently-computed correct outputs. If your code reproduces them, it is correct. If it doesn't, it is wrong, and you fix it *now*, before building anything on top.

The vectors in this book were generated with Python's `cryptography` library, deliberately not with the Java code you're writing — otherwise they'd only prove your code agrees with itself.

---

## Bill of materials

| Item | Notes | Approx |
|------|-------|--------|
| Raspberry Pi Zero 2 W | Any Pi works; the Zero 2 W is the tight fit that makes you optimise | €18 |
| RC522 reader module | The ubiquitous €3 MFRC522 breakout | €3 |
| **MIFARE DESFire EV2 or EV3 card** | ⚠️ **Not** Classic, not NTAG. See below. | €3 ea |
| 7″ touchscreen (optional) | 1024×600 HDMI+USB touch, for the kiosk | €45 |
| Jumper wires, microSD (16 GB+), 5 V 2.5 A PSU | | |
| An Android phone with NFC | For Part 4 | |

### ⚠️ The card matters more than anything else on this list

**MIFARE DESFire EV2 or EV3.** Not MIFARE Classic (broken crypto, completely different protocol), not NTAG or Ultralight (no crypto at all, no value files), not "13.56 MHz RFID tag" from a bag of 100 on eBay.

When you buy, the listing must say **DESFire EV2** or **EV3**. If it says "compatible" or doesn't name the chip, it isn't one. Buy at least two — one to develop against and one spare, because it is genuinely possible to brick a card by changing a key to a value you then forget.

You can verify what you have in Chapter 6: a real DESFire answers `GetVersion` with a hardware type byte of `0x33` (EV2/EV3).

### One safety note before you start

Chapter 12's app can **change the keys on a card**. A DESFire card whose key you've changed and forgotten is scrap — there is no recovery, by design. That is the entire security property you're building.

So: use dedicated test cards. Never run provisioning against a card you didn't buy for this project — the DESFire in your wallet may well be your office badge, your transit pass, or your gym membership, and it will not survive the experience.

---

## Conventions

- Java 21, Maven, package `com.midnightbrewer`. Keeping the same package as the reference repo means you can diff against it when stuck.
- All hex in this book is lowercase and unspaced in code, spaced in prose: `91 00`.
- `⚠️` marks a trap that has actually bitten someone.
- Each chapter ends with a suggested commit message, so your git history becomes a record of the build — useful for the video, and useful when a chapter breaks something that worked two chapters ago.

---

*Next: [00 — Setup](00-setup.md)*
