package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.util.Hex;

/**
 * One DESFire response: the body, and the status byte that followed it.
 *
 * <p>Splitting the two at the point of parsing means no caller has to remember
 * that the last two bytes of a response are not data -- a mistake that shows up
 * as two stray bytes in the middle of a decoded structure.
 */
public final class DesfireResponse {

    private final byte[] body;
    private final int status;

    DesfireResponse(byte[] body, int status) {
        this.body = body;
        this.status = status;
    }

    /** The response data, with SW1 and SW2 removed. */
    public byte[] body() {
        return body.clone();
    }

    /** SW2, the DESFire status byte. */
    public int status() {
        return status;
    }

    /** True if the status is {@code 0x00}. */
    public boolean isOk() {
        return status == DesfireStatus.OPERATION_OK.code();
    }

    /** True if another frame is waiting: status {@code 0xAF}. */
    public boolean hasMoreFrames() {
        return status == DesfireStatus.ADDITIONAL_FRAME.code();
    }

    @Override
    public String toString() {
        return Hex.encode(body) + " [" + DesfireStatus.describe(status) + "]";
    }
}
