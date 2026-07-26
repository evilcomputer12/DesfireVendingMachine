# 00 — Setup: hardware, Pi, and an empty project

**Goal:** an RC522 wired to a Pi, a Java project that compiles, and one green test. No card logic yet — this chapter is about removing every excuse the environment can give you later.

---

## 0.1 Wiring the RC522

The RC522 talks SPI. Six wires plus power.

| RC522 pin | Pi pin (physical) | Pi name | Why |
|-----------|-------------------|---------|-----|
| `3.3V` | 1 | 3V3 | ⚠️ **Never 5 V.** The MFRC522 is a 3.3 V part. 5 V kills it. |
| `RST` | 22 | BCM 25 | Hardware reset line |
| `GND` | 6 | GND | |
| `MISO` | 21 | BCM 9 (SPI0 MISO) | Chip → Pi |
| `MOSI` | 19 | BCM 10 (SPI0 MOSI) | Pi → chip |
| `SCK` | 23 | BCM 11 (SPI0 SCLK) | Clock |
| `SDA` | 24 | BCM 8 (SPI0 CE0) | Chip select. Confusingly labelled `SDA` (an I²C name) on most boards — it is the SPI CS pin. |
| `IRQ` | — | not connected | We poll instead. Simpler, and fast enough. |

**Use short wires.** This is not fussiness. At 1 MHz over 20 cm of loose DuPont jumper you get intermittent, maddening failures that look like firmware bugs — a version register that reads `0x00` one run and `0x92` the next. If the chip is flaky, shorten the wires before you debug a single line of code.

---

## 0.2 Pi OS and SPI

Flash Raspberry Pi OS (64-bit). Then enable SPI:

```bash
sudo raspi-config       # Interface Options → SPI → Enable
sudo reboot
ls -l /dev/spidev0.0    # must exist after reboot
```

`/dev/spidev0.0` is the kernel exposing "SPI bus 0, chip-select 0" as a file. That file is the entire hardware interface — everything in Part 1 is ultimately reads and writes to it.

Install Java and Maven:

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven
java -version    # expect 21.x
```

---

## 0.3 Create the project

You can develop on your laptop and deploy to the Pi, which is what I recommend — compiling on a Zero 2 W is slow enough to break your flow.

```bash
mkdir midnight-brewer && cd midnight-brewer
git init
mkdir -p src/main/java/com/midnightbrewer/hardware
mkdir -p src/test/java/com/midnightbrewer/hardware
```

### `pom.xml`

Maven's project file. Three things matter here: Java 21, JUnit 5 for tests, and Pi4J for SPI access.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.midnightbrewer</groupId>
  <artifactId>MidnightBrewer</artifactId>
  <version>1.0-SNAPSHOT</version>

  <properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <pi4j.version>3.0.2</pi4j.version>
  </properties>

  <dependencies>
    <!-- Pi4J: Java access to the Pi's SPI and GPIO. -->
    <dependency>
      <groupId>com.pi4j</groupId>
      <artifactId>pi4j-core</artifactId>
      <version>${pi4j.version}</version>
    </dependency>
    <!-- The SPI provider: talks to /dev/spidev0.0 through the Linux filesystem. -->
    <dependency>
      <groupId>com.pi4j</groupId>
      <artifactId>pi4j-plugin-linuxfs</artifactId>
      <version>${pi4j.version}</version>
    </dependency>
    <!-- The GPIO provider, for the reset pin. -->
    <dependency>
      <groupId>com.pi4j</groupId>
      <artifactId>pi4j-plugin-gpiod</artifactId>
      <version>${pi4j.version}</version>
    </dependency>
    <!-- Pi4J logs through SLF4J; without a binding it prints warnings. -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>2.0.13</version>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
    </plugins>
  </build>
</project>
```

> ⚠️ **`<scope>test</scope>` on JUnit.** It means "this library exists when compiling and running tests, but is not shipped with the application." Get it wrong and your kiosk drags a test framework onto the Pi. It also *enforces* the boundary: if you accidentally `import org.junit` in main code, it won't compile.

### Sanity check

```bash
mvn -q compile     # should print nothing and exit 0
```

Nothing to compile yet — that's fine. You've proven Maven, Java, and the network can all do their jobs.

---

## 0.4 A word about Java, if you're coming from C

You'll meet the rest as you go, but four things trip up every C programmer on day one:

**1. Every function lives inside a class.** There are no free functions. `Math.abs()` is a function `abs` inside a class `Math`. A file `Foo.java` contains `class Foo`. This feels bureaucratic and mostly is, but it's why the language can guarantee things C can't.

**2. `byte` is signed, and there's no `unsigned`.** This will bite you within the hour.

```java
byte b = (byte) 0x92;
System.out.println(b);          // -110   ← the same bits, read as signed
System.out.println(b & 0xFF);   // 146    ← what you meant
```
`0x92` is `1001 0010`. With the top bit set, Java reads it as a negative number. The fix is the idiom `& 0xFF`, which promotes to `int` and masks off the sign extension. **Any time you turn a `byte` into a number, write `& 0xFF`.** You will forget this and spend an hour on it. Everyone does.

**3. Arrays know their own length** (`arr.length`, no parentheses) and are bounds-checked. Reading past the end throws an exception instead of silently returning your neighbour's memory. This turns C's worst class of bug into a stack trace that names the line.

**4. No manual memory management.** No `malloc`, no `free`, no leaks of the classic kind. `new byte[16]` gives you sixteen zeroed bytes and they vanish when nothing refers to them.

One C habit that transfers perfectly: bit manipulation. `<<`, `>>`, `&`, `|`, `^`, `~` all mean exactly what they mean in C. The register-poking half of this project will feel like home.

---

## Commit

```bash
git add -A
git commit -m "chore: empty Maven project, Java 21, Pi4J + JUnit"
```

---

*Next: [01 — The SPI seam](01-spi-seam.md)*
