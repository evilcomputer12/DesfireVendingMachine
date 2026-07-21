package com.midnightbrewer.hardware;

/**
 * M2 — the RC522 driver.
 *
 * This is where "encapsulation" earns its keep. The messy details -- the
 * address-byte encoding, the read/write bit, the two-byte SPI dance -- all
 * hide IN HERE. The rest of your program will call readRegister(0x37) and
 * never once write ((reg << 1) & 0x7E) | 0x80 again. That is the whole point
 * of a class: gather the mess in one place and expose a clean set of verbs.
 *
 * Fill in the method bodies by translating the C shown above each one. The
 * scaffolding (the class, the field, the constructor, the register numbers)
 * is done so you can focus on the translation.
 */
public class Rc522Driver {

    // ── register numbers we need (from RC522.h) ──────────────────────
    // These are just addresses from the datasheet; nothing to translate.
    private static final int COMMAND_REG     = 0x01;  // CommandReg
    private static final int TX_CONTROL_REG  = 0x14;  // TxControlReg (antenna)
    private static final int VERSION_REG     = 0x37;  // VersionReg   (0x91/0x92)

    private static final int PCD_SOFT_RESET  = 0x0F;  // the reset command byte

    // ── composition: the driver HAS-A SpiLink ────────────────────────
    //
    // This is the encapsulation lesson. The driver does not KNOW whether it
    // is talking to a real chip or your FakeSpiLink -- it only knows it has
    // "something that can transfer bytes." 'private' means nobody outside can
    // reach in and touch the bus directly. 'final' means once set in the
    // constructor it never changes.
    private final SpiLink link;

    // The constructor is handed the SpiLink from outside (this is called
    // "dependency injection"). Because of it, a test can pass a FakeSpiLink
    // and the real code can pass a Pi4jSpiLink -- SAME driver, no change.
    public Rc522Driver(SpiLink link) {
        this.link = link;
    }

    // ═════════════════════════════════════════════════════════════════
    // readRegister
    //
    //   C:  uchar Read_MFRC522(uchar addr) {
    //           // CS LOW is handled for us by SpiLink.transfer(), so skip it
    //           RC522_SPI_Transfer( ((addr<<1)&0x7E) | 0x80 );  // address, read bit set
    //           val = RC522_SPI_Transfer(0x00);                 // second byte = the answer
    //           // CS HIGH also handled for us
    //           return val;
    //       }
    //
    //   In Java: one transfer() moves BOTH bytes at once. Build a 2-byte tx
    //   (the encoded address, then 0x00), a 2-byte rx, call link.transfer,
    //   and the answer is in rx[1].
    //   Return it as an int, and mask with & 0xFF so a byte like 0x92 comes
    //   back as 146, not -110 (the signed-byte thing again).
    // ═════════════════════════════════════════════════════════════════
    public int readRegister(int register) throws SpiException {
        byte[] tx = { (byte) (((register << 1) & 0x7E) | 0x80), 0x00 };
        byte[] rx = new byte[2];
        link.transfer(tx, rx, 2);
        return rx[1] & 0xFF;
      }

    // ═════════════════════════════════════════════════════════════════
    // writeRegister
    //
    //   C:  void Write_MFRC522(uchar addr, uchar val) {
    //           RC522_SPI_Transfer( (addr<<1)&0x7E );  // address, read bit CLEAR = write
    //           RC522_SPI_Transfer(val);               // the data byte
    //       }
    //
    //   Almost the same as readRegister, but: the address byte does NOT set
    //   0x80 (that is what makes it a write), and the second byte is 'value'
    //   instead of 0x00. There is no answer to read back.
    // ═════════════════════════════════════════════════════════════════
    public void writeRegister(int register, int value) throws SpiException {
        byte[] tx = { (byte) (((register << 1) & 0x7E)), (byte) (value & 0xFF) };
        byte[] rx = new byte[2];
        link.transfer(tx, rx, 2);
    }

    // ═════════════════════════════════════════════════════════════════
    // setBitMask  -- turn some bits ON without disturbing the others
    //
    //   C:  void SetBitMask(uchar reg, uchar mask) {
    //           tmp = Read_MFRC522(reg);
    //           Write_MFRC522(reg, tmp | mask);   // OR the bits in
    //       }
    //
    //   This is "read-modify-write": read what's there, OR in the mask, write
    //   it back. You already have readRegister and writeRegister -- use them.
    // ═════════════════════════════════════════════════════════════════
    public void setBitMask(int register, int mask) throws SpiException {
        int tmp = readRegister(register);
        writeRegister(register, tmp | mask);
    }

    // ═════════════════════════════════════════════════════════════════
    // clearBitMask -- turn some bits OFF without disturbing the others
    //
    //   C:  void ClearBitMask(uchar reg, uchar mask) {
    //           tmp = Read_MFRC522(reg);
    //           Write_MFRC522(reg, tmp & (~mask));  // AND with the inverse
    //       }
    //
    //   Same shape as setBitMask, but AND with ~mask instead of OR with mask.
    //   In Java, ~ is the bitwise NOT, same as C.
    // ═════════════════════════════════════════════════════════════════
    public void clearBitMask(int register, int mask) throws SpiException {
        int tmp = readRegister(register);
        writeRegister(register, tmp & (~mask));
    }

    // ═════════════════════════════════════════════════════════════════
    // softReset
    //
    //   C:  void MFRC522_Reset(void) {
    //           Write_MFRC522(CommandReg, PCD_RESETPHASE);   // 0x0F
    //       }
    //
    //   One line: write the reset command (PCD_SOFT_RESET) to COMMAND_REG.
    // ═════════════════════════════════════════════════════════════════
    public void softReset() throws SpiException {
        writeRegister(COMMAND_REG, PCD_SOFT_RESET);
    }

    // ═════════════════════════════════════════════════════════════════
    // antennaOn -- power up the RF field
    //
    //   C:  void AntennaOn(void) {
    //           Read_MFRC522(TxControlReg);     // (the C reads then ignores it)
    //           SetBitMask(TxControlReg, 0x03); // turn on Tx1RFEn | Tx2RFEn
    //       }
    //
    //   The two low bits (0x03) of TX_CONTROL_REG are the antenna drivers.
    //   Turn them on with setBitMask. This is the read-modify-write you saw
    //   working earlier -- TxControlReg goes 0x80 -> 0x83.
    // ═════════════════════════════════════════════════════════════════
    public void antennaOn() throws SpiException {
        readRegister(TX_CONTROL_REG); // read and ignore
        setBitMask(TX_CONTROL_REG, 0x03); // turn on Tx1RFEn | Tx2RFEn      
    }

    // ═════════════════════════════════════════════════════════════════
    // version -- read the chip's version byte (should be 0x91 or 0x92)
    //
    //   No C to translate -- it is just a readRegister of VERSION_REG. This
    //   is the method your test will call, exactly like the fake test.
    // ═════════════════════════════════════════════════════════════════
    public int version() throws SpiException {
        return readRegister(VERSION_REG);
    }
}
