package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.util.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The CreateValueFile payload layout and the access-rights word.
 *
 * <p>The serialised payload is pinned against the same bytes fed to
 * {@code df_cmac} when generating the {@code createvalue_cmdmac} vector from the
 * compiled C, so the on-wire order (little-endian limits, then the
 * limited-credit flag) is fixed here.
 */
class ValueFileSettingsTest {

    @Test
    void serialisesToTheSeventeenByteLayout() {
        // file 2, CommMode.Full, rights 0x2220, lower 0, upper 1000000, value 2500, no limited credit.
        ValueFileSettings settings = ValueFileSettings.builder(0x02)
                .accessRights(AccessRights.forValueFile())
                .lowerLimit(0)
                .upperLimit(1_000_000)
                .initialValue(2500)
                .build();
        assertArrayEquals(Hex.decode("020320220000000040420F00C409000000"), settings.toBytes());
    }

    @Test
    void accessRightsPackAndUnpack() {
        AccessRights rights = AccessRights.forValueFile();
        assertEquals(0x2220, rights.toWord());
        assertArrayEquals(Hex.decode("2022"), rights.toBytes());

        AccessRights single = AccessRights.singleKey(2);
        assertEquals(0x2222, single.toWord());

        AccessRights roundTrip = AccessRights.fromWord(0x1EF0);
        assertEquals(0x1EF0, roundTrip.toWord());
    }

    @Test
    void rejectsLimitsInInvalidOrder() {
        assertThrows(IllegalArgumentException.class,
                () -> ValueFileSettings.builder(2).lowerLimit(100).upperLimit(50).build());
    }

    @Test
    void rejectsInitialValueOutsideLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> ValueFileSettings.builder(2)
                        .lowerLimit(0).upperLimit(1000).initialValue(5000).build());
    }

    @Test
    void defaultWalletProfileMatchesTheFirmwareConstants() {
        WalletProfile profile = WalletProfile.defaults();
        assertEquals(0x010203, profile.aid());
        assertEquals(0x02, profile.fileNo());
        assertEquals(0x02, profile.userKeyNo());
        assertArrayEquals(new byte[16], profile.piccMasterKey());
        assertArrayEquals(filled(0x11), profile.appMasterKey());
        assertArrayEquals(filled(0x22), profile.appUserKey());
        assertEquals(2500, profile.initialBalance());
    }

    private static byte[] filled(int value) {
        byte[] out = new byte[16];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }
}
