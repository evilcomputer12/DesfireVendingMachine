package com.midnightbrewer.hardware;

/**
 * Runs YOUR driver AND YOUR reader against the real RC522 on the Pi.
 *
 * Two payoffs in one run:
 *   1. Rc522Driver reads the chip's version (M2).
 *   2. Rc522Reader.isCardPresent() detects a real card (M3) -- the abstract
 *      base's algorithm calling your transceive().
 *
 * The same driver your unit test drives with a FakeSpiLink; here it gets a
 * Pi4jSpiLink. One part swapped, the rest unchanged.
 */
public final class HardwareCheck {

    private HardwareCheck() {
    }

    // How YOUR card was personalized -- these MUST match the application the
    // top-up side created, or auth fails with an RndA/permission error.
    private static final byte[] APP_AID      = {0x03, 0x02, 0x01}; // AID 0x010203, LSB-first
    private static final byte   APP_KEY_NO   = 0x02;
    private static final byte   APP_KEY_BYTE = 0x22;              // key = 16 x 0x22

    public static void main(String[] args) throws Exception {
        System.out.println("Opening the reader (your Pi4jSpiLink)...");

        try (Pi4jSpiLink link = new Pi4jSpiLink()) {
            Rc522Driver driver = new Rc522Driver(link);

            // Bring the chip up. (This init sequence really belongs in a
            // driver.init() method -- a nice refactor for later. For now the
            // runner does it, using your driver's writeRegister.)
            driver.softReset();
            Thread.sleep(50);
            initChip(driver);

            int version = driver.version();
            System.out.printf("version()          = 0x%02X%n", version);
            if (version != 0x91 && version != 0x92) {
                System.out.println("  -> chip not answering; stopping.");
                return;
            }
            System.out.println("  -> M2 driver works on silicon.");

            // ── M3: your reader, detecting a real card ───────────────
            Rc522Reader reader = new Rc522Reader(driver);
            System.out.println("Polling isCardPresent() 15x (put a card on the antenna)...");
            int hits = 0;
            for (int i = 0; i < 15; i++) {
                if (reader.isCardPresent()) {
                    hits++;
                }
                Thread.sleep(40);
            }
            System.out.printf("card detected in %d / 15 polls%n", hits);
            if (hits > 0) {
                System.out.println("  -> YOUR Rc522Reader detected a real card. M3 works on silicon.");

                // ── M5: full activation (anticollision + SELECT + cascade + RATS) ──
                if (reader.isCardPresent()) {          // wake the card to READY
                    try {
                        ActivatedCard card = reader.activate();
                        System.out.println("UID  = " + card.uidHex());
                        System.out.printf("SAK  = 0x%02X%n", card.sak());
                        byte[] ats = card.ats();
                        StringBuilder atsHex = new StringBuilder();
                        for (byte b : ats) {
                            atsHex.append(String.format("%02X ", b));
                        }
                        System.out.println("ATS  = " + atsHex.toString().trim());
                        System.out.println("  -> YOUR reader activated the card. M5 complete on silicon.");

                        // ── M6: send an APDU over ISO-DEP ────────────
                        IsoDepChannel channel = new IsoDepChannel(reader);
                        // ISO-7816 wrapped DESFire GetVersion: 90 60 00 00 00
                        byte[] getVersion = {(byte) 0x90, 0x60, 0x00, 0x00, 0x00};
                        byte[] resp = channel.transceive(getVersion);
                        StringBuilder rHex = new StringBuilder();
                        for (byte b : resp) {
                            rHex.append(String.format("%02X ", b));
                        }
                        System.out.println("GetVersion resp = " + rHex.toString().trim());
                        if (resp.length >= 2 && (resp[resp.length - 2] & 0xFF) == 0x91) {
                            int sw2 = resp[resp.length - 1] & 0xFF;
                            System.out.printf("  -> DESFire answered (status 0x%02X). M6 ISO-DEP works!%n", sw2);
                            if (sw2 == 0xAF) {
                                System.out.println("     (0xAF = more frames -- the full version needs the M7 loop)");
                            }
                        } else if (resp.length == 0) {
                            System.out.println("  -> empty response (fill the M6 TODOs).");
                        }

                        // Drain GetVersion's remaining frames so the card goes
                        // idle again (it chains 3: HW, SW, UID -> AF, AF, 00).
                        byte[] af = {(byte) 0x90, (byte) 0xAF, 0x00, 0x00, 0x00};
                        channel.transceive(af);
                        channel.transceive(af);

                        // ── M7c: YOUR crypto authenticating with the card ──
                        try {
                            DesfireCard desfire =
                                    new DesfireCard(channel, RandomSource.secure());
                            desfire.selectApplication(APP_AID);
                            byte[] appKey = new byte[16];
                            java.util.Arrays.fill(appKey, APP_KEY_BYTE);
                            desfire.authenticateEv2First(APP_KEY_NO, appKey);
                            System.out.println("  -> MUTUAL AUTH OK. TI = "
                                    + hex(desfire.getTi()));
                            System.out.println("     M7c complete on silicon: "
                                    + "YOUR crypto authenticated a real DESFire!");
                        } catch (SpiException e) {
                            System.out.println("  -> M7c auth failed: " + e.getMessage());
                            System.out.println("     (check APP_AID / APP_KEY_NO / "
                                    + "APP_KEY_BYTE match how the card was set up)");
                        }
                    } catch (RuntimeException e) {
                        System.out.println("activate() not finished yet: " + e.getMessage());
                    }
                }
            } else {
                System.out.println("  -> no card seen. Is one flat on the antenna?");
            }
        }

        System.out.println("Reader closed.");
    }

    /**
     * The MFRC522_Init register writes, minus the parts already done
     * (softReset, antennaOn). TxAutoReg = 0x40 (force 100% ASK) and
     * ModeReg = 0x3D (CRC preset) are the ones a Type A card actually needs.
     */
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }

    private static void initChip(Rc522Driver driver) throws SpiException {
        driver.writeRegister(0x2A, 0x8D); // TModeReg    } the response
        driver.writeRegister(0x2B, 0x3E); // TPrescaler  } timer, ~300 ms
        driver.writeRegister(0x2C, 2);    // TReloadH    }
        driver.writeRegister(0x2D, 88);   // TReloadL    }
        driver.writeRegister(0x15, 0x40); // TxAutoReg  -> force 100% ASK
        driver.writeRegister(0x11, 0x3D); // ModeReg    -> CRC preset 0x6363
        driver.writeRegister(0x26, 0x08); // RFCfgReg   -> RX gain
        driver.antennaOn();               // your driver's method
    }
}
