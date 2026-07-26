# Midnight Brewer — project skeleton

This is the folder you build the whole project in. It compiles and passes its
tests right now, before you have written anything — which means when something
breaks later, you know it was *you*, not the setup.

## First run (laptop, no hardware)

```bash
mvn test
```

Expect **`Tests run: 3, Failures: 0`**. Read the number, not just the word
`SUCCESS` — a test framework that finds zero tests also reports success.

```bash
mvn -q compile
java -cp target/classes com.midnightbrewer.ToolchainCheck
```

That prints your Java version and confirms the toolchain. Still no hardware.

## Talking to the Pi

Set your Pi's address once, in your shell profile:

```bash
export PI=martin@raspberrypi.local
```

Then:

```bash
make pi-setup       # ONE TIME: installs Java/Maven, enables SPI, adds groups
make deploy-full    # build here, copy classes + dependency jars over
make run            # run ToolchainCheck on the Pi
make probe          # read the RC522's version register
```

`make` on its own lists every target.

## What each piece is

```
pom.xml                    what Maven builds and which libraries to fetch
Makefile                   the commands you actually type
.gitignore                 keeps target/ and secrets out of git
scripts/
  pi-bootstrap.sh          run once ON the Pi: Java, Maven, SPI, groups, swap
  deploy.sh                build locally, rsync classes to the Pi
  run.sh                   run a main class on the Pi
src/main/java/com/midnightbrewer/
  ToolchainCheck.java      proves Java + Maven + classpath work
  diag/SpiProbe.java       proves the RC522 is wired correctly
src/test/java/com/midnightbrewer/
  ToolchainCheckTest.java  proves JUnit runs and assertions bite
```

## Why deploy sends classes, not source

Java bytecode is architecture-independent, so a laptop build runs unchanged on
the Pi's ARM chip. Compiling on a Zero 2 W takes ~40 seconds; copying classes
takes ~5. Keep the slow machine out of your feedback loop.

You only need `make deploy-full` when `pom.xml` changes — the dependency jars
don't change otherwise.

## The two diagnostics, and why they're separate

| | Answers | Needs |
|---|---|---|
| `ToolchainCheck` | *Is my Java/Maven/classpath right?* | nothing |
| `SpiProbe` | *Is the reader wired correctly?* | Pi + RC522 |

Keeping "is it the code?" apart from "is it the wiring?" is worth a whole class.
When your own driver misbehaves in Chapter 2 of the main book, run `make probe`
and you'll know instantly which one to blame.

`SpiProbe` is deliberately ugly — one flat method, no abstractions. You'll write
the proper version yourself in Chapters 1–2. Delete it then, or keep it as a
field diagnostic, which is what it's good for.

## Next

Work through **`../book/setup-book.pdf`** first — it covers everything from
flashing the SD card to SSH keys to your first `git push`.

When `make probe` prints `WIRING OK`, you're ready for Chapter 1 of the main
book (`../../book/brewer-book.pdf`), where you start writing the stack.
