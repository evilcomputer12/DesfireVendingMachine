package com.midnightbrewer.reference.support;

import com.midnightbrewer.reference.pcd.crc.SoftwareCrcCalculator;
import com.midnightbrewer.reference.spi.SpiLink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * An in-memory MFRC522: register file, FIFO, CRC coprocessor and interrupt
 * flags, sitting behind {@link SpiLink}.
 *
 * <p>This is what makes the protocol layers testable. It is not a stub that
 * returns canned bytes -- it models the chip closely enough that the driver has
 * to drive it correctly to get anything out of it. In particular:
 *
 * <ul>
 *   <li>a transceive fires when StartSend is set in {@code BitFramingReg}
 *       <em>while {@code CommandReg} holds Transceive</em>, so a port that
 *       wrote those two registers in the wrong order would get silence;</li>
 *   <li>{@code CommIrqReg} and {@code DivIrqReg} honour the Set1 bit, so
 *       {@code ClearBitMask} has to be used correctly to clear a flag;</li>
 *   <li>the CRC coprocessor runs on a {@code CalcCRC} command and publishes to
 *       {@code CRCResultReg}, so the hardware CRC path is exercised for real;</li>
 *   <li>reads and writes are decoded from the RC522's own address encoding, so
 *       a wrong shift or direction bit shows up immediately.</li>
 * </ul>
 *
 * <p>The card behind it is a {@link SimulatedPicc}, so RF behaviour and chip
 * behaviour stay separate.
 */
public final class SimulatedRc522 implements SpiLink {

    /** Registers 0x00..0x3F. */
    private static final int REGISTER_COUNT = 0x40;

    private static final int REG_COMMAND = 0x01;
    private static final int REG_COMM_IRQ = 0x04;
    private static final int REG_DIV_IRQ = 0x05;
    private static final int REG_ERROR = 0x06;
    private static final int REG_STATUS2 = 0x08;
    private static final int REG_FIFO_DATA = 0x09;
    private static final int REG_FIFO_LEVEL = 0x0A;
    private static final int REG_CONTROL = 0x0C;
    private static final int REG_BIT_FRAMING = 0x0D;
    private static final int REG_TX_CONTROL = 0x14;
    private static final int REG_CRC_RESULT_HIGH = 0x21;
    private static final int REG_CRC_RESULT_LOW = 0x22;
    private static final int REG_VERSION = 0x37;

    private static final int CMD_IDLE = 0x00;
    private static final int CMD_CALC_CRC = 0x03;
    private static final int CMD_TRANSCEIVE = 0x0C;
    private static final int CMD_SOFT_RESET = 0x0F;

    private static final int IRQ_TIMER = 0x01;
    private static final int IRQ_IDLE = 0x10;
    private static final int IRQ_RX = 0x20;

    /** What a real MFRC522 v2.0 reports, and what this board reports. */
    public static final int VERSION_MFRC522_V2 = 0x92;

    /** {@code TxControlReg} after reset: drivers off. */
    private static final int TX_CONTROL_AFTER_RESET = 0x80;

    private final int[] registers = new int[REGISTER_COUNT];
    private final Deque<Integer> fifo = new ArrayDeque<>();
    // Typed as the concrete class on purpose: the software calculator cannot
    // fail, so the simulator does not have to model a CRC error path.
    private final SoftwareCrcCalculator crc = new SoftwareCrcCalculator();
    private final List<byte[]> transmittedFrames = new ArrayList<>();

    private SimulatedPicc picc;
    private int receiveLastBits;
    private boolean closed;

    public SimulatedRc522() {
        this(SimulatedPicc.silent());
    }

    public SimulatedRc522(SimulatedPicc picc) {
        this.picc = picc;
        applyResetDefaults();
    }

    /** Swaps the card in the field. */
    public void setPicc(SimulatedPicc picc) {
        this.picc = picc;
    }

    /** Every frame the reader has transmitted, in order. */
    public List<byte[]> transmittedFrames() {
        return List.copyOf(transmittedFrames);
    }

    /** Forgets the transmitted frame history. */
    public void clearTransmittedFrames() {
        transmittedFrames.clear();
    }

    /** Current value of a register, for assertions. */
    public int registerValue(int address) {
        return registers[address];
    }

    /** True if either antenna driver bit is set. */
    public boolean isAntennaOn() {
        return (registers[REG_TX_CONTROL] & 0x03) != 0;
    }

    // ------------------------------------------------------------ SPI decoding

    @Override
    public void transfer(byte[] tx, byte[] rx, int length) {
        if (closed) {
            throw new IllegalStateException("link is closed");
        }
        if (tx.length < length || rx.length < length) {
            throw new IllegalArgumentException("buffer shorter than " + length);
        }
        if (length != 2) {
            throw new IllegalArgumentException(
                    "the RC522 protocol is always two bytes per access, got " + length);
        }

        int address = tx[0] & 0xFF;
        boolean isRead = (address & 0x80) != 0;
        int register = (address >> 1) & 0x3F;

        if (isRead) {
            rx[0] = 0;
            rx[1] = (byte) readRegister(register);
        } else {
            writeRegister(register, tx[1] & 0xFF);
            rx[0] = 0;
            rx[1] = 0;
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    // -------------------------------------------------------- register model

    private int readRegister(int register) {
        switch (register) {
            case REG_FIFO_LEVEL:
                return fifo.size();
            case REG_FIFO_DATA:
                // A real chip returns rubbish from an empty FIFO rather than
                // failing; zero is as good a rubbish as any.
                Integer value = fifo.poll();
                return value == null ? 0 : value;
            case REG_CONTROL:
                return receiveLastBits & 0x07;
            case REG_VERSION:
                return VERSION_MFRC522_V2;
            default:
                return registers[register];
        }
    }

    private void writeRegister(int register, int value) {
        switch (register) {
            case REG_FIFO_DATA:
                fifo.add(value);
                return;

            case REG_FIFO_LEVEL:
                // Bit 7 is FlushBuffer; the rest of the register is read-only.
                if ((value & 0x80) != 0) {
                    fifo.clear();
                }
                return;

            case REG_COMM_IRQ:
            case REG_DIV_IRQ:
                // Bit 7 is Set1: set the marked bits when it is 1, clear them
                // when it is 0. This is what makes ClearBitMask work.
                if ((value & 0x80) != 0) {
                    registers[register] |= value & 0x7F;
                } else {
                    registers[register] &= ~(value & 0x7F);
                }
                return;

            case REG_COMMAND:
                registers[register] = value;
                if (value == CMD_CALC_CRC) {
                    runCrcCoprocessor();
                } else if (value == CMD_SOFT_RESET) {
                    applyResetDefaults();
                }
                return;

            case REG_BIT_FRAMING:
                registers[register] = value;
                // StartSend, and only meaningful while Transceive is loaded.
                if ((value & 0x80) != 0 && registers[REG_COMMAND] == CMD_TRANSCEIVE) {
                    runTransceive();
                }
                return;

            default:
                registers[register] = value;
        }
    }

    private void applyResetDefaults() {
        java.util.Arrays.fill(registers, 0);
        registers[REG_COMMAND] = CMD_IDLE;
        registers[REG_TX_CONTROL] = TX_CONTROL_AFTER_RESET;
        registers[REG_ERROR] = 0;
        registers[REG_STATUS2] = 0;
        fifo.clear();
        receiveLastBits = 0;
    }

    private void runCrcCoprocessor() {
        byte[] data = fifoSnapshot();
        byte[] result = crc.calculate(data, 0, data.length);
        registers[REG_CRC_RESULT_LOW] = result[0] & 0xFF;
        registers[REG_CRC_RESULT_HIGH] = result[1] & 0xFF;
        registers[REG_DIV_IRQ] |= 0x04;   // CRCIrq
    }

    private void runTransceive() {
        byte[] request = drainFifo();
        transmittedFrames.add(request);

        int txLastBits = registers[REG_BIT_FRAMING] & 0x07;
        byte[] response = picc.exchange(request, txLastBits);

        if (response == null) {
            // Nothing answered: the hardware timer expires. That is the flag
            // the driver turns into PcdStatus.NO_TAG.
            registers[REG_COMM_IRQ] |= IRQ_TIMER;
            return;
        }
        for (byte b : response) {
            fifo.add(b & 0xFF);
        }
        receiveLastBits = 0;
        registers[REG_COMM_IRQ] |= IRQ_RX | IRQ_IDLE;
    }

    private byte[] fifoSnapshot() {
        byte[] out = new byte[fifo.size()];
        int i = 0;
        for (Integer value : fifo) {
            out[i++] = (byte) (int) value;
        }
        return out;
    }

    private byte[] drainFifo() {
        byte[] out = fifoSnapshot();
        fifo.clear();
        return out;
    }
}
