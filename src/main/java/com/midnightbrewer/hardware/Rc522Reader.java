package com.midnightbrewer.hardware;

public class Rc522Reader extends Iso14443Reader {

    // ── registers this transceive needs (from RC522.h) ───────────────
    private static final int COMMAND_REG    = 0x01;
    private static final int COMM_IEN_REG   = 0x02; // which IRQs are enabled
    private static final int COMM_IRQ_REG   = 0x04; // which IRQs have fired
    private static final int ERROR_REG      = 0x06;
    private static final int FIFO_DATA_REG  = 0x09; // the FIFO in/out
    private static final int FIFO_LEVEL_REG = 0x0A; // how many bytes in the FIFO
    private static final int BIT_FRAMING_REG = 0x0D; // TxLastBits + StartSend

    private static final int PCD_IDLE       = 0x00; // "do nothing / cancel"
    private static final int PCD_TRANSCEIVE = 0x0C; // "send the FIFO, then receive"

    private final Rc522Driver driver;

    public Rc522Reader(Rc522Driver driver)
    {
        this.driver = driver;
    }

    // ═════════════════════════════════════════════════════════════════
    // transceive -- the RC522's "send a frame, get the answer" dance.
    //
    // This is your translation of MFRC522_ToCard (+ the BitFraming setup from
    // MFRC522_Request). Every line uses a driver method YOU wrote. The C
    // toggled the chip-select pin by hand; you don't -- your writeRegister /
    // readRegister already do one clean transaction each.
    //
    // Fill the TODOs in order.
    // ═════════════════════════════════════════════════════════════════
    @Override
    protected byte[] transceive(byte[] sendData, int bitsInLastByte) throws SpiException {

        // ── 1. tell the chip how many bits are in the last byte ──────
        //   C:  Write_MFRC522(BitFramingReg, 0x07);   // 7 for REQA
        //   The low 3 bits of BitFramingReg are TxLastBits.
        // TODO: driver.writeRegister(BIT_FRAMING_REG, bitsInLastByte & 0x07);
        driver.writeRegister(BIT_FRAMING_REG, bitsInLastByte & 0x07);

        // ── 2. arm the chip and empty the FIFO ───────────────────────
        //   C:  Write_MFRC522(CommIEnReg, 0x77|0x80);   // enable IRQs
        //       ClearBitMask(CommIrqReg, 0x80);         // clear old IRQ flags
        //       SetBitMask(FIFOLevelReg, 0x80);         // flush the FIFO
        //       Write_MFRC522(CommandReg, PCD_IDLE);    // cancel any command
        // TODO: four calls -- writeRegister, clearBitMask, setBitMask, writeRegister.
        driver.writeRegister(COMM_IEN_REG, 0x77 | 0x80);
        driver.clearBitMask(COMM_IRQ_REG, 0x80);         // clear old IRQ flags
        driver.setBitMask(FIFO_LEVEL_REG, 0x80);         // flush the FIFO
        driver.writeRegister(COMMAND_REG, PCD_IDLE);    // cancel any command

        // ── 3. load the bytes to send into the FIFO ──────────────────
        //   C:  for (i=0; i<sendLen; i++) Write_MFRC522(FIFODataReg, sendData[i]);
        // TODO: a for-loop writing each byte of sendData to FIFO_DATA_REG.
        //       (write it as an int: sendData[i] & 0xFF)

        for(int i = 0; i < sendData.length; i++){
            driver.writeRegister(FIFO_DATA_REG, sendData[i] & 0xFF);
        }

        // ── 4. run it, and press "start sending" ─────────────────────
        //   C:  Write_MFRC522(CommandReg, PCD_TRANSCEIVE);
        //       SetBitMask(BitFramingReg, 0x80);   // StartSend = bit 7
        // TODO: writeRegister(COMMAND_REG, PCD_TRANSCEIVE); then setBitMask.
        driver.writeRegister(COMMAND_REG, PCD_TRANSCEIVE);
        driver.setBitMask(BIT_FRAMING_REG, 0x80);   // StartSend = bit 7

        // ── 5. wait for the answer (or a timeout) ────────────────────
        //   The C spins a huge counter. That does NOT port -- on the Pi each
        //   readRegister is a syscall, so a big count runs for minutes. Use a
        //   wall-clock deadline instead (the lesson from the probe).
        //   We're done when RxIRq or IdleIRq fires (0x30), or TimerIRq (0x01).
        //
        //   long deadline = System.nanoTime() + 50_000_000L;   // ~50 ms
        //   int irq;
        //   do {
        //       irq = driver.readRegister(COMM_IRQ_REG);
        //   } while (System.nanoTime() < deadline && (irq & 0x01) == 0 && (irq & 0x30) == 0);
        // TODO: the loop above. Keep 'irq' -- step 6 needs it.
        long deadline = System.nanoTime() + 50_000_000L;   // ~50 ms
        int irq;
        do {
            irq = driver.readRegister(COMM_IRQ_REG);
        } while (System.nanoTime() < deadline && (irq & 0x01) == 0 && (irq & 0x30) == 0);

        // ── 6. stop sending, then check for trouble ──────────────────
        //   C:  ClearBitMask(BitFramingReg, 0x80);      // StartSend = 0
        //       if (Read_MFRC522(ErrorReg) & 0x1B) ...  // CRC/collision/etc
        //   Also: if TimerIRq fired (irq & 0x01), nobody answered -> no card.
        //   In either bad case, return an EMPTY array (the base reads that as
        //   "no card", which is not an error).
        // TODO: clearBitMask; if error bits set -> return new byte[0];
        //       if ((irq & 0x01) != 0) -> return new byte[0];
        driver.clearBitMask(BIT_FRAMING_REG, 0x80);      // StartSend = 0
        if((driver.readRegister(ERROR_REG) & 0x1B) != 0){
            return new byte[0];
        }
        if((irq & 0x01) !=0)
        {
            return new byte[0];
        }

        // ── 7. read the reply out of the FIFO ────────────────────────
        //   C:  n = Read_MFRC522(FIFOLevelReg);          // how many bytes
        //       for (i=0; i<n; i++) backData[i] = Read_MFRC522(FIFODataReg);
        // TODO: read FIFO_LEVEL_REG into n; make a new byte[n]; loop reading
        //       FIFO_DATA_REG into each slot (cast each to (byte)); return it.
        int n = driver.readRegister(FIFO_LEVEL_REG);// how many bytes
        byte[] receivedData = new byte[n];
        for(int i = 0; i < n; i++)
        {
            receivedData[i] = (byte)(driver.readRegister(FIFO_DATA_REG)); // always read from FIFO_DATA_REG
        }
        return receivedData;
    }
}
