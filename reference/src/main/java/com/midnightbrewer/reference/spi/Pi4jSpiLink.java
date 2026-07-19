package com.midnightbrewer.reference.spi;

import com.midnightbrewer.reference.error.TransportException;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiChipSelect;
import com.pi4j.io.spi.SpiMode;

/**
 * The Raspberry Pi implementation of {@link SpiLink}, and the only class in the
 * module that touches Pi4J.
 *
 * <p>It also owns the RC522's reset line, because reset and SPI are one
 * physical concern: the chip ignores SPI entirely while RST is low, so a link
 * that could be opened without raising RST would be a link that silently does
 * nothing.
 *
 * <p>The SPI parameters -- bus 0, CE0, mode 0, 1 MHz -- and the reset pin on
 * BCM 25 are the configuration confirmed working on this hardware, where
 * {@code VersionReg} reads back {@code 0x92} (MFRC522 v2.0). The register
 * address encoding lives one layer up in the driver; this class only moves
 * bytes.
 *
 * <p>No provider class is imported. Pi4J finds {@code linuxfs} and {@code gpiod}
 * on the runtime classpath by itself, which is what keeps the rest of the
 * module buildable on a laptop.
 */
public final class Pi4jSpiLink implements SpiLink {

    /** BCM numbering; physical pin 22 on the 40-pin header. */
    public static final int DEFAULT_RESET_GPIO = 25;

    /** The RC522 tolerates up to 10 MHz, but 1 MHz is what this board is verified at. */
    public static final int DEFAULT_BAUD_HZ = 1_000_000;

    /**
     * Settling time after releasing the reset line, copied from the verified
     * {@code Rc522Probe}. The datasheet's power-up time is far shorter, but the
     * probe that reads {@code 0x92} reliably on this board waits this long, and
     * there is no reason to shave it.
     */
    private static final long RESET_RELEASE_SETTLE_MS = 50L;

    private final Context pi4j;
    private final Spi spi;
    private final DigitalOutput resetLine;
    private final boolean ownsContext;

    private boolean closed;

    /**
     * Opens the link with the verified default wiring, creating and owning a
     * Pi4J context.
     */
    public static Pi4jSpiLink openDefault() throws TransportException {
        return open(DEFAULT_BAUD_HZ, DEFAULT_RESET_GPIO);
    }

    /**
     * Opens the link on SPI0/CE0 with a caller-chosen clock and reset pin,
     * creating and owning a Pi4J context.
     */
    public static Pi4jSpiLink open(int baudHz, int resetGpio) throws TransportException {
        Context context;
        try {
            context = Pi4J.newAutoContext();
        } catch (RuntimeException e) {
            throw new TransportException("could not start Pi4J (is this a Raspberry Pi?)", e);
        }
        try {
            return new Pi4jSpiLink(context, baudHz, resetGpio, true);
        } catch (TransportException | RuntimeException e) {
            context.shutdown();
            throw e;
        }
    }

    /**
     * Opens the link inside a Pi4J context the caller owns and will shut down.
     * Use this when the host application already runs Pi4J for other I/O.
     */
    public static Pi4jSpiLink openIn(Context context, int baudHz, int resetGpio)
            throws TransportException {
        return new Pi4jSpiLink(context, baudHz, resetGpio, false);
    }

    private Pi4jSpiLink(Context context, int baudHz, int resetGpio, boolean ownsContext)
            throws TransportException {
        this.pi4j = context;
        this.ownsContext = ownsContext;
        try {
            // RST must be high before any register will answer: the RC522 holds
            // itself in reset while the pin is low. shutdown(LOW) parks the chip
            // in reset when the JVM exits so it does not keep energising the coil.
            this.resetLine = context.create(
                    DigitalOutput.newConfigBuilder(context)
                            .id("rc522-rst")
                            .name("RC522 reset")
                            .address(resetGpio)
                            .initial(DigitalState.HIGH)
                            .shutdown(DigitalState.LOW)
                            .build());
            this.resetLine.state(DigitalState.HIGH);
            sleep(RESET_RELEASE_SETTLE_MS);

            this.spi = context.create(
                    Spi.newConfigBuilder(context)
                            .id("rc522-spi")
                            .name("RC522")
                            .bus(SpiBus.BUS_0)
                            .chipSelect(SpiChipSelect.CS_0)
                            .mode(SpiMode.MODE_0)
                            .baud(baudHz)
                            .build());
        } catch (RuntimeException e) {
            throw new TransportException(
                    "could not open SPI0/CE0 or GPIO " + resetGpio
                            + " (check /dev/spidev0.0 exists and this user may read it)", e);
        }
    }

    @Override
    public void transfer(byte[] tx, byte[] rx, int length) throws TransportException {
        if (tx.length < length || rx.length < length) {
            throw new IllegalArgumentException(
                    "buffers shorter than requested transfer length " + length);
        }
        if (closed) {
            throw new TransportException("SPI link is closed");
        }
        try {
            spi.transfer(tx, rx, length);
        } catch (RuntimeException e) {
            throw new TransportException("SPI transfer of " + length + " bytes failed", e);
        }
    }

    /** True if the reset line is currently released, so the chip can respond. */
    public boolean isResetReleased() {
        return resetLine.state() == DigitalState.HIGH;
    }

    /**
     * Drives a hard reset by pulsing RST low.
     *
     * <p>The C driver has no equivalent -- on the STM32 the pin is simply
     * strapped high at init -- but on the Pi a previous JVM run can leave the
     * chip in an unknown state, and a hard reset is cheaper to reason about
     * than a soft one.
     */
    public void pulseReset(long lowMillis) {
        resetLine.state(DigitalState.LOW);
        sleep(Math.max(1L, lowMillis));
        resetLine.state(DigitalState.HIGH);
        sleep(RESET_RELEASE_SETTLE_MS);
    }

    @Override
    public void close() throws TransportException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            spi.close();
        } catch (RuntimeException e) {
            throw new TransportException("failed to close SPI device", e);
        } finally {
            if (ownsContext) {
                pi4j.shutdown();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
