package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.util.Hex;

import java.util.Arrays;

/**
 * The decoded reply to DESFire GetVersion.
 *
 * <p>The card answers in three frames, concatenated here into 28 bytes:
 *
 * <pre>
 *   bytes  0..6   hardware: vendor, type, subtype, major, minor, storage, protocol
 *   bytes  7..13  software: same seven fields
 *   bytes 14..27  production: 7-byte UID, 5-byte batch number, week, year
 * </pre>
 *
 * <p>Immutable, and it holds the raw bytes as well as the decoded fields --
 * during bring-up the raw form is what gets compared against a working
 * firmware log, and a decoder that discards its input cannot be checked.
 */
public final class DesfireVersion {

    /** Total length of a complete three-frame GetVersion reply. */
    public static final int FULL_LENGTH = 28;

    /** Length of one hardware or software version block. */
    private static final int VERSION_BLOCK_LENGTH = 7;

    private final byte[] raw;

    private DesfireVersion(byte[] raw) {
        this.raw = raw;
    }

    /**
     * Parses a concatenated GetVersion reply.
     *
     * <p>Accepts short input rather than rejecting it: a truncated reply is
     * exactly what a half-working RF link produces, and seeing the first seven
     * bytes decoded is more useful during bring-up than an exception. The
     * accessors report {@code -1} for fields that were not received.
     */
    public static DesfireVersion parse(byte[] bytes) {
        return new DesfireVersion(bytes.clone());
    }

    /** The bytes as received. */
    public byte[] rawBytes() {
        return raw.clone();
    }

    /** True if all three frames arrived. */
    public boolean isComplete() {
        return raw.length >= FULL_LENGTH;
    }

    // ---------------------------------------------------------------- hardware

    /** Hardware vendor ID. {@code 0x04} is NXP. */
    public int hardwareVendor() {
        return byteAt(0);
    }

    /** Hardware type. */
    public int hardwareType() {
        return byteAt(1);
    }

    /** Hardware subtype. */
    public int hardwareSubtype() {
        return byteAt(2);
    }

    /** Hardware major version. {@code 0x01} is EV1, {@code 0x12} is EV2, {@code 0x33} is EV3. */
    public int hardwareMajorVersion() {
        return byteAt(3);
    }

    /** Hardware minor version. */
    public int hardwareMinorVersion() {
        return byteAt(4);
    }

    /** Raw storage size byte. See {@link #storageSizeBytes()}. */
    public int hardwareStorageSize() {
        return byteAt(5);
    }

    /** Communication protocol type. {@code 0x05} is ISO 14443-2 and -3. */
    public int hardwareProtocol() {
        return byteAt(6);
    }

    // ---------------------------------------------------------------- software

    /** Software vendor ID. */
    public int softwareVendor() {
        return byteAt(VERSION_BLOCK_LENGTH);
    }

    /** Software major version. */
    public int softwareMajorVersion() {
        return byteAt(VERSION_BLOCK_LENGTH + 3);
    }

    /** Software minor version. */
    public int softwareMinorVersion() {
        return byteAt(VERSION_BLOCK_LENGTH + 4);
    }

    // -------------------------------------------------------------- production

    /** The card's 7-byte UID, as reported by the card rather than by anticollision. */
    public byte[] uid() {
        return slice(14, 7);
    }

    /** The 5-byte production batch number. */
    public byte[] batchNumber() {
        return slice(21, 5);
    }

    /** Production calendar week, BCD. */
    public int productionWeek() {
        return bcd(byteAt(26));
    }

    /** Production year, BCD, offset from 2000. */
    public int productionYear() {
        int year = bcd(byteAt(27));
        return year < 0 ? -1 : 2000 + year;
    }

    // ------------------------------------------------------------ interpretation

    /**
     * Usable EEPROM in bytes, decoded from the storage size byte.
     *
     * <p>The byte is an exponent in bits 7..1. If bit 0 is clear the size is
     * exactly {@code 2^n}; if set, it is somewhere between {@code 2^n} and
     * {@code 2^(n+1)}, and this returns the lower bound. {@code 0x18} is the
     * common 4 KB DESFire.
     */
    public long storageSizeBytes() {
        int raw = hardwareStorageSize();
        if (raw < 0) {
            return -1;
        }
        return 1L << (raw >>> 1);
    }

    /** True if the storage size byte says the real size is a range, not exact. */
    public boolean isStorageSizeApproximate() {
        int raw = hardwareStorageSize();
        return raw >= 0 && (raw & 0x01) != 0;
    }

    /** A best-effort product name from the hardware major version. */
    public String productName() {
        if (hardwareVendor() != 0x04) {
            return "non-NXP card (vendor " + Hex.byteToString(hardwareVendor()) + ")";
        }
        switch (hardwareMajorVersion()) {
            case 0x01: return "MIFARE DESFire EV1";
            case 0x12: return "MIFARE DESFire EV2";
            case 0x33: return "MIFARE DESFire EV3";
            case 0x00: return "MIFARE DESFire (original)";
            default: return "MIFARE DESFire, unknown generation "
                    + Hex.byteToString(hardwareMajorVersion());
        }
    }

    /** A multi-line summary for the bring-up console. */
    public String toReport() {
        StringBuilder out = new StringBuilder();
        out.append("  product      : ").append(productName()).append('\n');
        out.append("  hardware     : vendor=").append(Hex.byteToString(hardwareVendor()))
                .append(" type=").append(Hex.byteToString(hardwareType()))
                .append(" subtype=").append(Hex.byteToString(hardwareSubtype()))
                .append(" version=").append(hardwareMajorVersion())
                .append('.').append(hardwareMinorVersion())
                .append(" protocol=").append(Hex.byteToString(hardwareProtocol()))
                .append('\n');
        out.append("  software     : vendor=").append(Hex.byteToString(softwareVendor()))
                .append(" version=").append(softwareMajorVersion())
                .append('.').append(softwareMinorVersion())
                .append('\n');
        out.append("  storage      : ").append(storageSizeBytes()).append(" bytes")
                .append(isStorageSizeApproximate() ? " (lower bound)" : " (exact)")
                .append('\n');
        if (isComplete()) {
            out.append("  card UID     : ").append(Hex.encode(uid())).append('\n');
            out.append("  batch number : ").append(Hex.encode(batchNumber())).append('\n');
            out.append("  produced     : week ").append(productionWeek())
                    .append(" of ").append(productionYear()).append('\n');
        } else {
            out.append("  (incomplete reply: ").append(raw.length)
                    .append(" of ").append(FULL_LENGTH).append(" bytes)\n");
        }
        out.append("  raw          : ").append(Hex.encode(raw));
        return out.toString();
    }

    private int byteAt(int index) {
        return index < raw.length ? raw[index] & 0xFF : -1;
    }

    private byte[] slice(int offset, int length) {
        if (offset + length > raw.length) {
            return new byte[0];
        }
        return Arrays.copyOfRange(raw, offset, offset + length);
    }

    private static int bcd(int value) {
        if (value < 0) {
            return -1;
        }
        return ((value >> 4) & 0x0F) * 10 + (value & 0x0F);
    }

    @Override
    public String toString() {
        return productName() + " (" + storageSizeBytes() + " bytes)";
    }
}
