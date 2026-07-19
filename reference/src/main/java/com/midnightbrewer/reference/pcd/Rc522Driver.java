package com.midnightbrewer.reference.pcd;

import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.error.TransportException;
import com.midnightbrewer.reference.pcd.crc.CrcCalculator;
import com.midnightbrewer.reference.spi.SpiLink;
import com.midnightbrewer.reference.util.Timebase;

import java.util.Objects;

/**
 * The MFRC522 itself: registers, FIFO, antenna, timer and one raw exchange with
 * a card.
 *
 * <p>Ported from the register-level half of {@code RC522.c} --
 * {@code Read_MFRC522}, {@code Write_MFRC522}, {@code SetBitMask},
 * {@code ClearBitMask}, {@code AntennaOn}/{@code Off}, {@code MFRC522_Init},
 * {@code MFRC522_Reset}, {@code MFRC522_ToCard}, {@code MFRC522_DumpRegs} and
 * {@code MFRC522_FieldReset}.
 *
 * <p>It knows nothing about cards. It cannot tell REQA from an I-block; it
 * moves bytes into the FIFO, starts a command, waits for the right interrupt
 * and hands back what came out. Every protocol decision lives above it. That
 * boundary is what lets the ISO 14443-4 state machine be tested against a
 * simulated chip.
 *
 * <p>All hardware timings, register write orders and retry counts here are
 * reproduced from the C without adjustment; each one carries a comment saying
 * what it is for. They were tuned against real cards, and the failures caused
 * by "tidying" them do not look like software bugs.
 *
 * <p>Not thread-safe. One RC522, one owner, one conversation at a time -- which
 * is also true of the chip.
 */
public class Rc522Driver implements RegisterAccess, AutoCloseable {

    /**
     * Maximum bytes drained from the RX FIFO by a single transceive, from
     * {@code MFRC522_FIFO_READ_MAX}. It is also the physical FIFO depth.
     */
    public static final int FIFO_READ_MAX = 64;

    /**
     * Software backstop on the interrupt wait, from the C's {@code i = 2000000}.
     *
     * <p>The C's comment explains the requirement: the backstop must be far
     * longer than the hardware timer so the timer always fires first and the
     * driver reports {@code NO_TAG} rather than a generic error. It computes
     * ~4 us per iteration, so 2,000,000 iterations is about 8 seconds against a
     * 2 second timer.
     *
     * <p>The iteration count is preserved, but on a Pi one iteration is an SPI
     * syscall rather than a few instructions, so the count alone would allow
     * minutes rather than seconds. {@link #SOFTWARE_BACKSTOP_MILLIS} restores
     * the C's actual intent; whichever limit is reached first ends the wait.
     */
    private static final int SOFTWARE_BACKSTOP_ITERATIONS = 2_000_000;

    /** The wall-clock equivalent of the C's iteration backstop: ~8 s. */
    private static final long SOFTWARE_BACKSTOP_MILLIS = 8_000L;

    /**
     * {@code ErrorReg} bits that make a command a failure:
     * BufferOvfl | CollErr | CRCErr | ProtocolErr.
     */
    private static final int FATAL_ERROR_MASK = 0x1B;

    /** {@code CommIrqReg} bit 0, TimerIRq: the hardware timeout expired. */
    private static final int TIMER_IRQ = 0x01;

    /** {@code BitFramingReg} bit 7, StartSend. */
    private static final int START_SEND = 0x80;

    /**
     * Settle time after a soft reset. The C writes the next register
     * immediately, relying on the STM32's slower bus; the verified
     * {@code Rc522Probe} on this Pi waits 50 ms before reading
     * {@code VersionReg} and gets a reliable {@code 0x92}. Waiting is free and
     * cannot break anything the C does.
     */
    private static final long SOFT_RESET_SETTLE_MS = 50L;

    /** Minimum antenna-off time in a field reset, from {@code MFRC522_FieldReset}. */
    private static final long MINIMUM_FIELD_OFF_MS = 5L;

    /** Antenna settle time after the field comes back up, from the same function. */
    private static final long FIELD_ON_SETTLE_MS = 2L;

    private final SpiLink link;
    private final Timebase timebase;
    private final ProtocolTrace trace;
    private final CrcCalculator crcCalculator;

    /** Reused so a per-register SPI round trip does not allocate. */
    private final byte[] txBuffer = new byte[2];
    private final byte[] rxBuffer = new byte[2];

    /** Convenience constructor: real clock, no tracing. */
    public Rc522Driver(SpiLink link) {
        this(link, Timebase.system(), ProtocolTrace.none());
    }

    /**
     * @param link     the SPI transport; the driver takes ownership and closes it
     * @param timebase source of delays, so tests need not sleep
     * @param trace    where register and frame diagnostics go
     */
    public Rc522Driver(SpiLink link, Timebase timebase, ProtocolTrace trace) {
        this.link = Objects.requireNonNull(link, "link");
        this.timebase = Objects.requireNonNull(timebase, "timebase");
        this.trace = Objects.requireNonNull(trace, "trace");
        this.crcCalculator = new HardwareCrcCalculator(this, trace);
    }

    // ---------------------------------------------------------------- registers

    /**
     * {@code Read_MFRC522}. One two-byte transfer, not two one-byte transfers:
     * the C's comment explains that back-to-back frames let chip select glitch
     * high between them and corrupt the transaction.
     *
     * <p>The address goes out first with bit 7 set for a read, and the answer
     * arrives in the following byte slot, so the second byte of the reply is the
     * value.
     */
    @Override
    public int read(Register register) throws NfcException {
        txBuffer[0] = register.readAddressByte();
        txBuffer[1] = 0x00;                  // 8.1.2.1 table 6: zeros on a read
        link.transfer(txBuffer, rxBuffer, 2);
        return rxBuffer[1] & 0xFF;
    }

    /** {@code Write_MFRC522}. Address byte with bit 7 clear, then the value. */
    @Override
    public void write(Register register, int value) throws NfcException {
        txBuffer[0] = register.writeAddressByte();
        txBuffer[1] = (byte) value;
        link.transfer(txBuffer, rxBuffer, 2);
    }

    // ------------------------------------------------------------- chip control

    /** {@code MFRC522_Reset}: the soft reset command, with no wait. */
    public void softReset() throws NfcException {
        write(Register.COMMAND, PcdCommand.SOFT_RESET.code());
    }

    /**
     * {@code MFRC522_Init}. The register write order is the C's, unchanged.
     *
     * <p>What each line does:
     * <ul>
     *   <li>{@code TModeReg = 0x8D}, {@code TPrescalerReg = 0x3E} -- auto-start
     *       the timer at the end of transmission, prescaler {@code 0x0D3E}, so
     *       one tick is about 0.5 ms.</li>
     *   <li>{@code TReload = 600} -- about 300 ms. See {@link ResponseTimeout}.</li>
     *   <li>{@code TxAutoReg = 0x40} -- force 100% ASK modulation.</li>
     *   <li>{@code ModeReg = 0x3D} -- CRC preset {@code 0x6363}, which is what
     *       makes the coprocessor agree with
     *       {@link com.midnightbrewer.reference.pcd.crc.SoftwareCrcCalculator}.</li>
     *   <li>{@code RFCfgReg = 0x08} -- lower RX gain. The C's comment calls this
     *       "less aggressive field coupling"; raising it makes the reader see
     *       cards further away and read them less reliably.</li>
     * </ul>
     */
    public void initialise() throws NfcException {
        softReset();
        timebase.sleep(SOFT_RESET_SETTLE_MS);

        write(Register.TIMER_MODE, 0x8D);
        write(Register.TIMER_PRESCALER, 0x3E);
        applyTimeout(ResponseTimeout.DEFAULT);

        write(Register.TX_AUTO, 0x40);
        write(Register.MODE, 0x3D);
        write(Register.RF_CONFIG, 0x08);

        antennaOn();

        trace.log(() -> {
            try {
                return String.format("init: VersionReg=%02X TxControlReg=%02X RFCfgReg=%02X",
                        read(Register.VERSION),
                        read(Register.TX_CONTROL),
                        read(Register.RF_CONFIG));
            } catch (NfcException e) {
                return "init: register read-back failed: " + e.getMessage();
            }
        });
    }

    /**
     * {@code AntennaOn}. Enables both transmitter drivers, TX1 and TX2.
     *
     * <p>The leading read of {@code TxControlReg} is in the C and is preserved.
     * Its value is discarded, so it is either a settling read or a leftover, but
     * it is one SPI transaction on a path that runs once, and removing writes
     * from a working RF init is not a trade worth making.
     *
     * <p>This is not optional. After a reset {@code TxControlReg} reads
     * {@code 0x80}: the drivers are off and no card is visible at any distance.
     */
    public void antennaOn() throws NfcException {
        read(Register.TX_CONTROL);
        setBitMask(Register.TX_CONTROL, 0x03);
    }

    /**
     * {@code AntennaOff}. The C's comment notes that the field should be left
     * off for at least 1 ms between a stop and a start; {@link #fieldReset}
     * enforces a longer minimum.
     */
    public void antennaOff() throws NfcException {
        clearBitMask(Register.TX_CONTROL, 0x03);
    }

    /** True if either transmitter driver is enabled. */
    public boolean isAntennaOn() throws NfcException {
        return (read(Register.TX_CONTROL) & 0x03) != 0;
    }

    /**
     * {@code VersionReg}. {@code 0x91} is MFRC522 v1.0, {@code 0x92} is v2.0
     * (what this board reports), {@code 0x88} is a common clone. {@code 0x00}
     * or {@code 0xFF} means nothing is driving MISO.
     */
    public int version() throws NfcException {
        return read(Register.VERSION);
    }

    /** The CRC_A implementation bound to this chip's coprocessor. */
    public CrcCalculator crc() {
        return crcCalculator;
    }

    /** Installs one of the two timeout settings the C switches between. */
    public void applyTimeout(ResponseTimeout timeout) throws NfcException {
        write(Register.TIMER_RELOAD_HIGH, timeout.reloadHigh());
        write(Register.TIMER_RELOAD_LOW, timeout.reloadLow());
    }

    // -------------------------------------------------------------------- FIFO

    /** Current RX FIFO occupancy in bytes. */
    public int fifoLevel() throws NfcException {
        return read(Register.FIFO_LEVEL);
    }

    /** Pops one byte from the FIFO. Reading an empty FIFO yields rubbish, not an error. */
    public int readFifoByte() throws NfcException {
        return read(Register.FIFO_DATA);
    }

    /** FlushBuffer: discards the FIFO contents and resets its pointers. */
    public void flushFifo() throws NfcException {
        setBitMask(Register.FIFO_LEVEL, 0x80);
    }

    /**
     * Sets {@code BitFramingReg} to send a short final byte.
     *
     * <p>Zero means whole bytes. Seven is REQA and WUPA, which are 7-bit
     * frames -- {@code MFRC522_Request} writes {@code 0x07} and
     * {@code MFRC522_AnticollCascade} writes {@code 0x00} back. Writing the
     * whole register, as the C does, also clears StartSend and RxAlign.
     */
    public void setTransmitLastBits(int lastBits) throws NfcException {
        write(Register.BIT_FRAMING, lastBits & 0x07);
    }

    // --------------------------------------------------------------- exchanges

    /** Convenience overload sending the whole array. */
    public PcdResponse transceive(PcdCommand command, byte[] sendData) throws NfcException {
        return transceive(command, sendData, sendData.length);
    }

    /**
     * {@code MFRC522_ToCard}: load the FIFO, run a command, wait for the right
     * interrupt, drain the FIFO.
     *
     * <p>The order of operations is load-bearing and is the C's exactly:
     *
     * <ol>
     *   <li>enable the command's interrupts ({@code CommIEnReg}, bit 7 set so
     *       the write means "enable");</li>
     *   <li>clear all pending interrupt flags, so a stale flag cannot end the
     *       wait before the command has started;</li>
     *   <li>flush the FIFO;</li>
     *   <li>write {@code IDLE} to cancel whatever was running;</li>
     *   <li>fill the FIFO;</li>
     *   <li>write the command;</li>
     *   <li>for a transceive only, set StartSend -- transmission begins on
     *       <em>this</em> write, not on the command write.</li>
     * </ol>
     *
     * <p>Data is only drained for {@link PcdCommand#TRANSCEIVE}. For anything
     * else the C leaves its length out-parameter untouched, so the returned bit
     * count is zero.
     */
    public PcdResponse transceive(PcdCommand command, byte[] sendData, int sendLength)
            throws NfcException {
        final int interruptEnable = command.interruptEnableMask();
        final int waitInterrupts = command.waitInterruptMask();

        write(Register.COMM_INTERRUPT_ENABLE, interruptEnable | 0x80);
        clearBitMask(Register.COMM_INTERRUPT_REQUEST, 0x80);
        flushFifo();
        write(Register.COMMAND, PcdCommand.IDLE.code());

        for (int i = 0; i < sendLength; i++) {
            write(Register.FIFO_DATA, sendData[i]);
        }

        write(Register.COMMAND, command.code());
        if (command == PcdCommand.TRANSCEIVE) {
            setBitMask(Register.BIT_FRAMING, START_SEND);
        }

        final long backstopDeadline = timebase.millis() + SOFTWARE_BACKSTOP_MILLIS;
        int remaining = SOFTWARE_BACKSTOP_ITERATIONS;
        int irq;
        while (true) {
            // CommIrqReg[7..0] = Set1 TxIRq RxIRq IdleIRq HiAlertIRq LoAlertIRq ErrIRq TimerIRq
            irq = read(Register.COMM_INTERRUPT_REQUEST);
            remaining--;
            if ((irq & TIMER_IRQ) != 0 || (irq & waitInterrupts) != 0) {
                break;
            }
            if (remaining == 0) {
                break;
            }
            if (timebase.millis() >= backstopDeadline) {
                remaining = 0;
                break;
            }
        }

        clearBitMask(Register.BIT_FRAMING, START_SEND);

        if (remaining == 0) {
            // Backstop exhausted: the hardware timer never fired, which means
            // the chip stopped responding rather than the card being absent.
            trace.log("ToCard: software backstop expired waiting for CommIrq");
            return PcdResponse.failure(PcdStatus.ERROR);
        }

        if ((read(Register.ERROR) & FATAL_ERROR_MASK) != 0) {
            return PcdResponse.failure(PcdStatus.ERROR);
        }

        PcdStatus status = PcdStatus.OK;
        if ((irq & interruptEnable & TIMER_IRQ) != 0) {
            // Timer fired and the command had timer interrupts enabled: no card
            // answered inside the window.
            status = PcdStatus.NO_TAG;
        }

        if (command != PcdCommand.TRANSCEIVE) {
            return new PcdResponse(status, 0, new byte[0]);
        }

        int level = read(Register.FIFO_LEVEL);
        int lastBits = read(Register.CONTROL) & 0x07;
        int bitCount = lastBits != 0 ? (level - 1) * 8 + lastBits : level * 8;

        // The C's two guards, in its order: never read zero bytes, never read
        // past the FIFO. When the level is zero the single read is a dummy --
        // the reported bit count stays zero, and the ISO 14443-4 layer treats
        // the frame as still arriving.
        int toRead = level == 0 ? 1 : level;
        if (toRead > FIFO_READ_MAX) {
            toRead = FIFO_READ_MAX;
        }

        byte[] received = new byte[toRead];
        for (int i = 0; i < toRead; i++) {
            received[i] = (byte) read(Register.FIFO_DATA);
        }
        return new PcdResponse(status, bitCount, received);
    }

    // ------------------------------------------------------------- diagnostics

    /**
     * {@code MFRC522_DumpRegs}: the seven registers worth seeing when an
     * exchange has just failed.
     *
     * <p>Returned rather than printed so the caller decides whether it is a log
     * line or an exception message.
     */
    public String registerDump(String tag) throws NfcException {
        return String.format(
                "[RC522] %s: CMD=%02X IRQ=%02X ERR=%02X S1=%02X S2=%02X FIFO=%02X RF=%02X",
                tag == null ? "?" : tag,
                read(Register.COMMAND),
                read(Register.COMM_INTERRUPT_REQUEST),
                read(Register.ERROR),
                read(Register.STATUS1),
                read(Register.STATUS2),
                read(Register.FIFO_LEVEL),
                read(Register.RF_CONFIG));
    }

    /** Writes {@link #registerDump} to the trace. */
    public void traceRegisters(String tag) {
        if (!trace.isEnabled()) {
            return;
        }
        try {
            trace.log(registerDump(tag));
        } catch (NfcException e) {
            trace.log("register dump failed: " + e.getMessage());
        }
    }

    /**
     * {@code MFRC522_FieldReset}: drop the RF field long enough for every card
     * in range to power down, then bring it back.
     *
     * <p>This is the recovery of last resort. A card that has lost protocol
     * state -- half a chain sent, a block number nobody agrees on -- cannot be
     * talked back into line, but it can be power-cycled through the air.
     *
     * <p>The C's floor of 5 ms off is preserved: shorter and a card's supply
     * capacitor holds it alive through the gap, so it wakes with its old state
     * intact and nothing has been fixed. The 2 ms settle after the field returns
     * is preserved for the same reason in the other direction.
     *
     * <p>Callers that hold ISO 14443-4 state must reset it too; see
     * {@code Rc522IsoDepTransceiver.resetField}.
     */
    public void fieldReset(long offMillis) throws NfcException {
        long off = Math.max(MINIMUM_FIELD_OFF_MS, offMillis);

        write(Register.COMMAND, PcdCommand.IDLE.code());
        flushFifo();
        clearBitMask(Register.STATUS2, 0x08);   // MFCrypto1On = 0
        applyTimeout(ResponseTimeout.DEFAULT);

        antennaOff();
        timebase.sleep(off);
        antennaOn();
        timebase.sleep(FIELD_ON_SETTLE_MS);
    }

    /** The clock this driver was built with, so collaborators share one seam. */
    public Timebase timebase() {
        return timebase;
    }

    /** The trace this driver was built with. */
    public ProtocolTrace trace() {
        return trace;
    }

    /** Closes the SPI transport. The driver is unusable afterwards. */
    @Override
    public void close() throws TransportException {
        link.close();
    }
}
