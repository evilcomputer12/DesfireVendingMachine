package com.midnightbrewer.reference.pcd.crc;

/**
 * CRC_A in software, per ISO/IEC 14443-3 Annex B.
 *
 * <p>The preset is {@code 0x6363}, which is what {@code MFRC522_Init} selects
 * when it writes {@code 0x3D} to {@code ModeReg} -- the C's comment on that
 * line says "CRC Initial value 0x6363". So this implementation and the
 * coprocessor agree by construction, and the test suite checks that they do.
 *
 * <p>The reference vector is the MIFARE HALT frame: CRC_A over
 * {@code 50 00} is {@code 57 CD}.
 *
 * <p>Stateless and immutable, so one instance can be shared freely.
 */
public final class SoftwareCrcCalculator implements CrcCalculator {

    /**
     * ISO 14443-3 CRC_A preset. Selected on the chip by {@code ModeReg = 0x3D}.
     */
    private static final int PRESET = 0x6363;

    @Override
    public byte[] calculate(byte[] data, int offset, int length) {
        int crc = PRESET;
        for (int i = 0; i < length; i++) {
            // Annex B's byte-at-a-time form of the reflected 0x8408 polynomial.
            int b = (data[offset + i] ^ crc) & 0xFF;
            b = (b ^ (b << 4)) & 0xFF;
            crc = ((crc >>> 8) ^ (b << 8) ^ (b << 3) ^ (b >>> 4)) & 0xFFFF;
        }
        // Low byte first: the order the RC522 stores and transmits them in.
        return new byte[] {(byte) (crc & 0xFF), (byte) ((crc >>> 8) & 0xFF)};
    }

    @Override
    public String toString() {
        return "SoftwareCrcCalculator[CRC_A, preset=0x6363]";
    }
}
