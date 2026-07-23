package com.midnightbrewer.hardware;

public interface ApduChannel {
    byte[] transceive(byte[] apdu) throws SpiException;
}
