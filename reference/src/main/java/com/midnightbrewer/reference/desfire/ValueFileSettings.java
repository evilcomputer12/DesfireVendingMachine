package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.desfire.crypto.DesfireCrypto;

/**
 * The parameters of a DESFire value file, and the 17-byte {@code CreateValueFile}
 * payload they serialise to.
 *
 * <p>A value file holds one signed 32-bit little-endian integer plus a lower
 * limit, an upper limit and a limited-credit flag. This project reads the
 * integer as a euro balance in cents, so 12.50 EUR is the integer 1250.
 *
 * <p>Wire layout of the command data, from the NXP data sheet and matching the
 * Dart port's {@code ValueFileSettings.toBytes}:
 *
 * <pre>
 *   [0]      fileNo
 *   [1]      commSettings
 *   [2..3]   accessRights            little-endian
 *   [4..7]   lowerLimit              signed, little-endian
 *   [8..11]  upperLimit              signed, little-endian
 *   [12..15] value (initial)         signed, little-endian
 *   [16]     limitedCreditEnabled    0x00 or 0x01
 * </pre>
 *
 * <p>The C library has no value files at all -- its example uses a standard
 * data file -- so this is one of the two areas (with the value-file commands
 * themselves) inferred from the data sheet and the Dart port rather than ported
 * from C. The comm mode defaults to {@link CommMode#FULL}, the mode the value
 * commands run in.
 */
public final class ValueFileSettings {

    /** Serialised length of a CreateValueFile payload. */
    public static final int PAYLOAD_LENGTH = 17;

    private final int fileNo;
    private final int commMode;
    private final AccessRights accessRights;
    private final int lowerLimit;
    private final int upperLimit;
    private final int initialValue;
    private final boolean limitedCreditEnabled;

    private ValueFileSettings(Builder builder) {
        this.fileNo = builder.fileNo;
        this.commMode = builder.commMode;
        this.accessRights = builder.accessRights;
        this.lowerLimit = builder.lowerLimit;
        this.upperLimit = builder.upperLimit;
        this.initialValue = builder.initialValue;
        this.limitedCreditEnabled = builder.limitedCreditEnabled;
        if (lowerLimit > upperLimit) {
            throw new IllegalArgumentException(
                    "lowerLimit (" + lowerLimit + ") must not exceed upperLimit (" + upperLimit + ")");
        }
        if (initialValue < lowerLimit || initialValue > upperLimit) {
            throw new IllegalArgumentException(
                    "initialValue (" + initialValue + ") must lie within the limits");
        }
    }

    public int fileNo() {
        return fileNo;
    }

    /** Serialises the settings into the 17-byte CreateValueFile payload. */
    public byte[] toBytes() {
        byte[] out = new byte[PAYLOAD_LENGTH];
        out[0] = (byte) fileNo;
        out[1] = (byte) commMode;
        byte[] rights = accessRights.toBytes();
        out[2] = rights[0];
        out[3] = rights[1];
        System.arraycopy(DesfireCrypto.int32ToLittleEndian(lowerLimit), 0, out, 4, 4);
        System.arraycopy(DesfireCrypto.int32ToLittleEndian(upperLimit), 0, out, 8, 4);
        System.arraycopy(DesfireCrypto.int32ToLittleEndian(initialValue), 0, out, 12, 4);
        out[16] = (byte) (limitedCreditEnabled ? 0x01 : 0x00);
        return out;
    }

    /** A builder seeded with the value file the demo provisions: file 2, key-2 access. */
    public static Builder builder(int fileNo) {
        return new Builder(fileNo);
    }

    /** Fluent builder; every field except {@code fileNo} has a sensible default. */
    public static final class Builder {
        private final int fileNo;
        private int commMode = CommMode.FULL;
        private AccessRights accessRights = AccessRights.forValueFile();
        private int lowerLimit = 0;
        private int upperLimit = 1_000_000;
        private int initialValue = 0;
        private boolean limitedCreditEnabled = false;

        private Builder(int fileNo) {
            this.fileNo = fileNo & 0xFF;
        }

        public Builder commMode(int commMode) {
            this.commMode = commMode;
            return this;
        }

        public Builder accessRights(AccessRights accessRights) {
            this.accessRights = accessRights;
            return this;
        }

        public Builder lowerLimit(int lowerLimit) {
            this.lowerLimit = lowerLimit;
            return this;
        }

        public Builder upperLimit(int upperLimit) {
            this.upperLimit = upperLimit;
            return this;
        }

        public Builder initialValue(int initialValue) {
            this.initialValue = initialValue;
            return this;
        }

        public Builder limitedCreditEnabled(boolean enabled) {
            this.limitedCreditEnabled = enabled;
            return this;
        }

        public ValueFileSettings build() {
            return new ValueFileSettings(this);
        }
    }
}
