package com.midnightbrewer.reference.pcd;

import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.pcd.crc.CrcCalculator;

/**
 * CRC_A computed by the RC522's own coprocessor: a direct port of
 * {@code CalulateCRC}.
 *
 * <p>This is the implementation the driver uses on real hardware, because it is
 * the one the working firmware uses. It is slower than doing the arithmetic in
 * Java -- every byte costs an SPI round trip, and the ISO 14443-4 receive path
 * calls it once per candidate frame length when it has to find a frame boundary
 * -- but it is bit-for-bit what the card has been talking to.
 *
 * <p>Register order below is exactly the C's, and it matters: the FIFO must be
 * flushed and {@code CRCIrq} cleared <em>before</em> the data goes in, or the
 * completion poll returns immediately on a stale flag.
 */
public final class HardwareCrcCalculator implements CrcCalculator {

    /**
     * {@code DivIrqReg} bit 2, {@code CRCIrq}: set when the coprocessor is done.
     */
    private static final int CRC_IRQ = 0x04;

    /**
     * Poll budget, from the C's {@code i = 0xFF}. The coprocessor takes a few
     * microseconds, so 255 SPI reads is a large margin; it exists only so a
     * dead chip cannot hang the caller.
     */
    private static final int COMPLETION_POLL_ATTEMPTS = 0xFF;

    private final RegisterAccess registers;
    private final ProtocolTrace trace;

    public HardwareCrcCalculator(RegisterAccess registers, ProtocolTrace trace) {
        this.registers = registers;
        this.trace = trace;
    }

    @Override
    public byte[] calculate(byte[] data, int offset, int length) throws NfcException {
        registers.write(Register.COMMAND, PcdCommand.IDLE.code());
        registers.clearBitMask(Register.DIVERSE_INTERRUPT_REQUEST, CRC_IRQ);
        registers.setBitMask(Register.FIFO_LEVEL, 0x80);   // FlushBuffer: reset the FIFO pointer

        for (int i = 0; i < length; i++) {
            registers.write(Register.FIFO_DATA, data[offset + i]);
        }
        registers.write(Register.COMMAND, PcdCommand.CALCULATE_CRC.code());

        int attempts = COMPLETION_POLL_ATTEMPTS;
        int divIrq;
        do {
            divIrq = registers.read(Register.DIVERSE_INTERRUPT_REQUEST);
            attempts--;
        } while (attempts != 0 && (divIrq & CRC_IRQ) == 0);

        if ((divIrq & CRC_IRQ) == 0) {
            // The C reads the result registers regardless and returns whatever
            // is in them. That is preserved -- changing it would change which
            // bytes go on the air -- but it is traced, because the symptom is a
            // card that silently rejects an otherwise perfect frame.
            trace.log("CRC coprocessor did not raise CRCIrq within "
                    + COMPLETION_POLL_ATTEMPTS + " polls; using stale result registers");
        }

        // Low byte first, matching the on-air order.
        byte[] result = new byte[CRC_LENGTH];
        result[0] = (byte) registers.read(Register.CRC_RESULT_LOW);
        result[1] = (byte) registers.read(Register.CRC_RESULT_HIGH);
        return result;
    }

    @Override
    public String toString() {
        return "HardwareCrcCalculator[RC522 coprocessor]";
    }
}
