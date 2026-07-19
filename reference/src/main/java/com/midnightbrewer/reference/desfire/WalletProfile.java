package com.midnightbrewer.reference.desfire;

/**
 * The application constants that define this project's DESFire wallet.
 *
 * <p>The keys, key numbers, AID and file number are the ones the STM32 firmware
 * uses in {@code nucleof411re/Core/Src/desfiire.c}: AID {@code 0x010203}, app
 * master key sixteen {@code 0x11} bytes, app user key sixteen {@code 0x22}
 * bytes on key number 2, and the file's access-rights word {@code 0x2220}. The
 * PICC master key is the factory value -- sixteen zero bytes -- and, exactly as
 * in the reference C, it is only ever <em>used</em> to reach the card, never
 * changed, so a card this project touches stays recoverable by any other tool.
 *
 * <p>{@link #defaults()} builds the profile the wallet demo provisions and
 * operates on. The file is a value file rather than the firmware's standard
 * data file, so the balance lives in a single signed 32-bit counter the card
 * itself guards against underflow.
 */
public final class WalletProfile {

    private final int aid;
    private final int fileNo;
    private final int userKeyNo;
    private final byte[] piccMasterKey;
    private final byte[] appMasterKey;
    private final byte[] appUserKey;
    private final AccessRights accessRights;
    private final int lowerLimit;
    private final int upperLimit;
    private final int initialBalance;

    private WalletProfile(Builder builder) {
        this.aid = builder.aid;
        this.fileNo = builder.fileNo;
        this.userKeyNo = builder.userKeyNo;
        this.piccMasterKey = builder.piccMasterKey.clone();
        this.appMasterKey = builder.appMasterKey.clone();
        this.appUserKey = builder.appUserKey.clone();
        this.accessRights = builder.accessRights;
        this.lowerLimit = builder.lowerLimit;
        this.upperLimit = builder.upperLimit;
        this.initialBalance = builder.initialBalance;
    }

    /**
     * The wallet the demo uses: AID {@code 0x010203}, file 2, user key 2, keys
     * and access rights from the STM32 firmware, and a 2500-cent opening
     * balance.
     */
    public static WalletProfile defaults() {
        return new Builder().build();
    }

    public int aid() {
        return aid;
    }

    public int fileNo() {
        return fileNo;
    }

    public int userKeyNo() {
        return userKeyNo;
    }

    public byte[] piccMasterKey() {
        return piccMasterKey.clone();
    }

    public byte[] appMasterKey() {
        return appMasterKey.clone();
    }

    public byte[] appUserKey() {
        return appUserKey.clone();
    }

    public AccessRights accessRights() {
        return accessRights;
    }

    public int lowerLimit() {
        return lowerLimit;
    }

    public int upperLimit() {
        return upperLimit;
    }

    public int initialBalance() {
        return initialBalance;
    }

    /** A builder pre-seeded with the firmware's constants. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; every field defaults to the firmware/demo value. */
    public static final class Builder {
        private int aid = 0x010203;
        // Value file 0x01, matching flutter_topup's AppConfig.valueFileNo. File
        // 0x02 is the STM32 firmware's standard data file, so the wallet lives
        // in its own file and the two coexist on one card.
        private int fileNo = 0x01;
        private int userKeyNo = 0x02;
        private byte[] piccMasterKey = new byte[16];
        private byte[] appMasterKey = filled(0x11);
        private byte[] appUserKey = filled(0x22);
        private AccessRights accessRights = AccessRights.forValueFile();
        private int lowerLimit = 0;
        private int upperLimit = 1_000_000;
        private int initialBalance = 2500;

        public Builder aid(int aid) {
            this.aid = aid;
            return this;
        }

        public Builder fileNo(int fileNo) {
            this.fileNo = fileNo;
            return this;
        }

        public Builder userKeyNo(int userKeyNo) {
            this.userKeyNo = userKeyNo;
            return this;
        }

        public Builder piccMasterKey(byte[] key) {
            this.piccMasterKey = requireLength(key, "piccMasterKey");
            return this;
        }

        public Builder appMasterKey(byte[] key) {
            this.appMasterKey = requireLength(key, "appMasterKey");
            return this;
        }

        public Builder appUserKey(byte[] key) {
            this.appUserKey = requireLength(key, "appUserKey");
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

        public Builder initialBalance(int initialBalance) {
            this.initialBalance = initialBalance;
            return this;
        }

        public WalletProfile build() {
            return new WalletProfile(this);
        }

        private static byte[] filled(int value) {
            byte[] out = new byte[16];
            java.util.Arrays.fill(out, (byte) value);
            return out;
        }

        private static byte[] requireLength(byte[] key, String name) {
            if (key == null || key.length != 16) {
                throw new IllegalArgumentException(name + " must be 16 bytes");
            }
            return key.clone();
        }
    }
}
