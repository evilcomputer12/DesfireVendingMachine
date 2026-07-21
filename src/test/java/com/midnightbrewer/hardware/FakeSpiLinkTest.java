package com.midnightbrewer.hardware;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FakeSpiLinkTest {
    @Test
    void answerVersionRegisterWith0x92() throws SpiException {
        FakeSpiLink fakeSpiLink = new FakeSpiLink();
        byte[] txBuffer = new byte[2];
        byte[] rxBuffer = new byte[2];
        txBuffer[0] = (byte)0x6E;
        fakeSpiLink.transfer(txBuffer, rxBuffer, 2);
        assertEquals((byte)0x92, rxBuffer[1]);
    }
}
