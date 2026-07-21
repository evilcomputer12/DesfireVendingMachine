package com.midnightbrewer.hardware;

// These are the Pi4J classes you'll use. You can't guess import lines for a
// library you don't know, so they're filled in. Each one is explained below.
import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiChipSelect;
import com.pi4j.io.spi.SpiMode;

/**
 * The REAL SpiLink — talks to the actual RC522 over the Pi's SPI bus, using
 * the Pi4J library.
 *
 * This is the other appliance for your socket. FakeSpiLink answers from a
 * table; this one wiggles real pins. Your Rc522Driver can't tell them apart --
 * that's the whole point.
 *
 * You can't test this on a laptop (no SPI hardware). You prove it works by
 * running the driver against it on the Pi. The fake is what you test with.
 *
 * ── PI4J IN THREE SENTENCES ──────────────────────────────────────────
 *  1. A Context is your program's one connection to the Pi's hardware.
 *     You make it once with Pi4J.newAutoContext().
 *  2. From the context you CREATE devices (an SPI channel, a GPIO pin) by
 *     describing them with a "config builder" and calling context.create(...).
 *  3. Those device objects then have simple methods: spi.transfer(...),
 *     output.state(...), spi.close().
 */
public class Pi4jSpiLink implements SpiLink {

    // The RC522's hardware reset pin, wired to GPIO 25 (BCM numbering).
    // It must be driven HIGH or the chip stays asleep and answers nothing.
    private static final int RST_GPIO = 25;

    // We hold onto the context and the spi channel so close() can shut them
    // down later. (Same idea as the driver holding its SpiLink: a field.)
    private final Context pi4j;
    private final Spi spi;

    /**
     * Opens the reader. This is where all the Pi4J setup happens.
     *
     * Design note: this class makes its OWN Context. That's the simplest
     * choice for one reader. (If you ever had two SPI devices you'd make one
     * shared Context and pass it in -- the same "who owns the dependency"
     * question you met with the driver. You don't, so keep it simple.)
     */
    public Pi4jSpiLink() {
        // ── STEP 1: make the Context ─────────────────────────────────
        // EXAMPLE USAGE:
        //     Context ctx = Pi4J.newAutoContext();
        //
        Context ctx = Pi4J.newAutoContext();
        // TODO: assign it to the field 'pi4j'.
        this.pi4j = ctx; // <-- replace null with the example above

        // ── STEP 2: drive the RST pin HIGH so the chip wakes up ──────
        // A GPIO output pin is created just like an SPI channel: describe it
        // with a builder, then create it from the context.
        //
        // EXAMPLE USAGE:
        //     DigitalOutput rst = pi4j.create(
        //         DigitalOutput.newConfigBuilder(pi4j)
        //             .id("rc522-rst")            // any unique name
        //             .address(RST_GPIO)          // which GPIO pin (25)
        //             .initial(DigitalState.HIGH) // start it HIGH
        //             .shutdown(DigitalState.LOW) // put it LOW on shutdown
        //             .build());
        //     rst.state(DigitalState.HIGH);       // make sure it's HIGH now
        //
        // TODO: create the RST output and set it HIGH. It can be a local
        //       variable (no field needed) -- the context tracks it.
        DigitalOutput rst = pi4j.create(
            DigitalOutput.newConfigBuilder(pi4j)
            .id("rc522-rst")
            .address(RST_GPIO)
            .initial(DigitalState.HIGH)
            .shutdown(DigitalState.LOW)
            .build()
        );
        rst.state(DigitalState.HIGH);

        // ── STEP 3: build the SPI channel ────────────────────────────
        // Same pattern: a config builder describes it, context.create makes it.
        // These settings match your probe exactly (proven to work):
        //     bus 0, chip-select 0, mode 0, 1 MHz.
        //
        // EXAMPLE USAGE:
        //     Spi channel = pi4j.create(
        //         Spi.newConfigBuilder(pi4j)
        //             .id("rc522-spi")
        //             .bus(SpiBus.BUS_0)
        //             .chipSelect(SpiChipSelect.CS_0)
        //             .mode(SpiMode.MODE_0)
        //             .baud(1_000_000)
        //             .build());
        //
        Spi channel = pi4j.create(
            Spi.newConfigBuilder(pi4j)
            .id("rc522-spi")
            .bus(SpiBus.BUS_0)
            .chipSelect(SpiChipSelect.CS_0)
            .mode(SpiMode.MODE_0)
            .baud(1_000_000)
            .build()
        );

        // TODO: build the SPI channel and assign it to the field 'spi'.
        this.spi = channel; // <-- replace null with the example above
    }

    // ═════════════════════════════════════════════════════════════════
    // transfer -- the one method your driver actually calls.
    //
    // Pi4J's SPI object already does a full-duplex transfer with exactly the
    // signature you designed:  spi.transfer(tx, rx, length)
    //
    // EXAMPLE USAGE:
    //     spi.transfer(txBuffer, rxBuffer, length);
    //
    // ONE IMPORTANT THING: if the transfer fails, Pi4J throws ITS OWN kind of
    // exception (a RuntimeException). Your driver doesn't know Pi4J and
    // shouldn't have to -- so catch Pi4J's exception and re-throw it as YOUR
    // SpiException. This is exactly why you made that class: to keep the
    // library's types from leaking upward. (Same idea as not letting SpiLink
    // throw a card exception.)
    // ═════════════════════════════════════════════════════════════════
    @Override
    public void transfer(byte[] txBuffer, byte[] rxBuffer, int length) throws SpiException {
        // TODO:
        //   try {
        //       spi.transfer(txBuffer, rxBuffer, length);
        //   } catch (RuntimeException e) {
        //       throw new SpiException("SPI transfer failed: " + e.getMessage());
        //   }

        try {
            spi.transfer(txBuffer, rxBuffer, length);
        } catch (RuntimeException e) {
            throw new SpiException("SPI transfer failed: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // close -- hand the hardware back.
    //
    // Two things to release: the SPI channel and the Context. Do both.
    //
    // EXAMPLE USAGE:
    //     spi.close();
    //     pi4j.shutdown();
    // ═════════════════════════════════════════════════════════════════
    @Override
    public void close() throws SpiException {
        // TODO: close the spi channel, then shut down the context.
        spi.close();
        pi4j.shutdown();
    }
}
