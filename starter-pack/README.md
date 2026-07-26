# Starter Pack — Volume Zero

**Everything before Chapter 1.** A 98-page tutorial that takes you from a blank
SD card to a Raspberry Pi you can reach with one word, plus a project skeleton
that already builds and passes its tests.

Written for someone who has never used SSH, never generated a key pair, and has
never run `mvn` in their life.

```
starter-pack/
├── book/
│   ├── setup-book.pdf      ← read this first
│   └── *.tex               ← source; `make` rebuilds
├── template/               ← headless: start the card stack here
│   ├── pom.xml, Makefile, .gitignore
│   ├── scripts/            ← pi-bootstrap, deploy, run
│   └── src/                ← ToolchainCheck + SpiProbe + a test
└── template-gui/           ← the kiosk UI, for the GUI episodes
    ├── src/main/resources/ ← FXML + CSS + artwork, GIVEN COMPLETE
    └── src/main/java/      ← UIController with 13 TODOs, YOURS
```

**Two templates, on purpose.** `template/` is headless — Chapters 1–11 of
Volume One need no screen, and dragging JavaFX through them would mean a heavier
build and a desktop on the Pi from day one. `template-gui/` is the touchscreen,
split so the *design* is handed to you and the *behaviour* is what you write on
camera.

## Start here

```bash
open starter-pack/book/setup-book.pdf
```

Then, when the book tells you to:

```bash
cp -r starter-pack/template ~/midnight-brewer
cd ~/midnight-brewer
mvn test        # expect: Tests run: 3, Failures: 0
```

## What the book covers

| Part | Chapters |
|---|---|
| **Your machine** | what to buy · Java/Maven/git · enough terminal to be dangerous |
| **The Pi** | flashing headless · finding it · **SSH keys properly** · Java, SPI, groups, swap |
| **The workflow** | git & GitHub · the project skeleton · the 5-second deploy loop · editor setup |
| **The hardware** | wiring the RC522 and proving it answers |
| **Appendices** | cheat sheet · troubleshooting indexed by error message |

Every chapter ends with a **checkpoint** — a command and the output you should
see. Don't continue past a failed one; setup problems compound viciously.

## What the template gives you

Two diagnostics that answer different questions, deliberately kept separate:

| | Answers | Needs |
|---|---|---|
| `ToolchainCheck` | *Is my Java/Maven/classpath right?* | nothing |
| `SpiProbe` | *Is the reader wired correctly?* | Pi + RC522 |

Plus a `Makefile` that turns the whole workflow into four commands:

```bash
make pi-setup      # one time: Java, Maven, SPI, groups, swap on the Pi
make deploy-full   # build here, ship classes + jars there
make run           # run on the Pi
make probe         # is the reader alive?
```

**The deploy loop is ~5 seconds** — build on your laptop, run on the Pi. Java
bytecode is architecture-independent, so there's no reason to compile on a
512 MB machine.

## Then what

When `make probe` prints `WIRING OK`, open **Volume One**
(`../book/brewer-book.pdf`) and start writing the actual stack: an SPI seam, an
RC522 driver, ISO-14443 activation, AES-CMAC, mutual authentication, and a real
card debit.

## Rebuilding the book

```bash
cd starter-pack/book
make          # full build
make watch    # rebuild on save
```

Needs TeX Live. Shares the design system with Volume One — same callout boxes,
same code styles. See `../book/README.md` for the environment reference.
