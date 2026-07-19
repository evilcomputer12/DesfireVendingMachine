package com.midnightbrewer.logic;

/**
 * Interface representing a generic hardware bus (like SPI or I2C).
 * This abstracts away the underlying hardware connection, allowing us
 * to swap between a real Raspberry Pi SPI driver and a mocked software driver.
 */
public interface HardwareBus {

    /**
     * Initializes the connection to the hardware bus.
     * @throws Exception if initialization fails.
     */
    void init() throws Exception;

    /**
     * Writes a byte of data to a specific register address.
     *
     * @param registerAddress The address of the register to write to.
     * @param data The byte of data to write.
     */
    void writeRegister(byte registerAddress, byte data);

    /**
     * Reads a byte of data from a specific register address.
     *
     * @param registerAddress The address of the register to read from.
     * @return The byte of data read from the register.
     */
    byte readRegister(byte registerAddress);

    /**
     * Closes the connection to the hardware bus and releases resources.
     */
    void close();
}
