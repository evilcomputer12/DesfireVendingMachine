package com.midnightbrewer.hardware;

public class FakeSpiLink implements SpiLink{
    @Override
    public void close() throws SpiException {
        // no-op
    }

    @Override
    public void transfer(byte[] txBuffer, byte[] rxBuffer, int length) throws SpiException {
        // no-op
        int register = readRegister(txBuffer);
        if(register == 0x37)
        {
            rxBuffer[1] = (byte)0x92;
        }
    }

    private int readRegister(byte[] txBuffer) {
        int register = 0;
        if(txBuffer.length > 1)
        {
            register = (txBuffer[0] & 0x7E) >> 1;
        }
        return register;
    }
}
