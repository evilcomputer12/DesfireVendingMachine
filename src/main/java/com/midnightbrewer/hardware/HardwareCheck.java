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
