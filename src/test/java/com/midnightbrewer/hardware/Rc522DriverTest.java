package com.midnightbrewer.hardware;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class Rc522DriverTest {
    @Test
    void getVersion() throws SpiException{
        FakeSpiLink fakeSpiLink = new FakeSpiLink();
        Rc522Driver rc522Driver = new Rc522Driver(fakeSpiLink);
        int version = rc522Driver.version();
        assertEquals(0x92, version);

    }
}
