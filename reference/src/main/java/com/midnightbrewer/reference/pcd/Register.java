package com.midnightbrewer.reference.pcd;

/**
 * The MFRC522 register map, chapter 9 of the datasheet.
 *
 * <p>A direct transcription of the {@code #define} block in {@code RC522.h},
 * turned into an enum so that a register can never be confused with a value,
 * a bit mask or a command. In the C, {@code Write_MFRC522(0x14, 0x03)} and
 * {@code Write_MFRC522(0x03, 0x14)} are both well-typed; here only one of them
 * compiles.
 *
 * <p>Reserved registers are omitted: they exist in the header only to make the
 * address list contiguous, and writing to one is never intentional.
 */
public enum Register {

    // Page 0: command and status
    COMMAND(0x01),
    COMM_INTERRUPT_ENABLE(0x02),
    DIVERSE_INTERRUPT_ENABLE(0x03),
    COMM_INTERRUPT_REQUEST(0x04),
    DIVERSE_INTERRUPT_REQUEST(0x05),
    ERROR(0x06),
    STATUS1(0x07),
    STATUS2(0x08),
    FIFO_DATA(0x09),
    FIFO_LEVEL(0x0A),
    WATER_LEVEL(0x0B),
    CONTROL(0x0C),
    BIT_FRAMING(0x0D),
    COLLISION(0x0E),

    // Page 1: command
    MODE(0x11),
    TX_MODE(0x12),
    RX_MODE(0x13),
    TX_CONTROL(0x14),
    TX_AUTO(0x15),
    TX_SELECT(0x16),
    RX_SELECT(0x17),
    RX_THRESHOLD(0x18),
    DEMOD(0x19),
    MIFARE(0x1C),
    SERIAL_SPEED(0x1F),

    // Page 2: configuration
    CRC_RESULT_HIGH(0x21),
    CRC_RESULT_LOW(0x22),
    MOD_WIDTH(0x24),
    RF_CONFIG(0x26),
    GS_N(0x27),
    CW_GS_P(0x28),
    MOD_GS_P(0x29),
    TIMER_MODE(0x2A),
    TIMER_PRESCALER(0x2B),
    TIMER_RELOAD_HIGH(0x2C),
    TIMER_RELOAD_LOW(0x2D),
    TIMER_COUNTER_VALUE_HIGH(0x2E),
    TIMER_COUNTER_VALUE_LOW(0x2F),

    // Page 3: test registers
    TEST_SELECT1(0x31),
    TEST_SELECT2(0x32),
    TEST_PIN_ENABLE(0x33),
    TEST_PIN_VALUE(0x34),
    TEST_BUS(0x35),
    AUTO_TEST(0x36),
    VERSION(0x37),
    ANALOG_TEST(0x38),
    TEST_DAC1(0x39),
    TEST_DAC2(0x3A),
    TEST_ADC(0x3B);

    private final int address;

    Register(int address) {
        this.address = address;
    }

    /** The 6-bit register address, before the RC522's SPI shift and direction bit. */
    public int address() {
        return address;
    }

    /**
     * The SPI address byte for reading this register.
     *
     * <p>Section 8.1.2.1, table 6: the address is shifted left one bit, masked
     * to bits 6..1, and bit 7 carries the direction. This is the encoding the
     * verified {@code Rc522Probe} uses to read {@code 0x92} from
     * {@link #VERSION}.
     */
    public byte readAddressByte() {
        return (byte) (((address << 1) & 0x7E) | 0x80);
    }

    /** The SPI address byte for writing this register: same shift, bit 7 clear. */
    public byte writeAddressByte() {
        return (byte) ((address << 1) & 0x7E);
    }

    @Override
    public String toString() {
        return name() + String.format("(0x%02X)", address);
    }
}
