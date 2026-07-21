package com.midnightbrewer.hardware;

public interface SpiLink extends AutoCloseable {
    void transfer(byte[] txBuffer, byte[] rxBuffer, int length) throws SpiException;

    @Override
    void close() throws SpiException;
}
