package com.midnightbrewer.reference.iso14443;

import com.midnightbrewer.reference.pcd.PcdResponse;

import java.util.Arrays;

/**
 * The frame assembly buffer, standing in for the C's
 * {@code uint8_t res[256]} global and its companion {@code resBytes}.
 *
 * <p>It looks like a plain byte array with a length, and it has to, because the
 * C's receive path depends on a subtlety that a tidier container would hide.
 * {@code MFRC522_ToCard} drains at most 64 bytes into the buffer but reports a
 * bit count taken from the <em>full</em> FIFO level. The ISO 14443-4 layer then
 * sets the length from that bit count and appends whatever is still in the FIFO
 * <em>at that offset</em>. So the write position and the number of bytes
 * actually written are two different things, on purpose: it is how a frame that
 * was still arriving when the command ended gets stitched back together.
 *
 * <p>Hence {@link #acceptCommandData} and {@link #truncateTo} being separate
 * operations, and {@link #byteAt} reading past the current length without
 * complaint. The C reads {@code res[0]} of a zeroed buffer when a frame comes
 * back empty and treats the resulting {@code 0x00} as an I-block PCB; that
 * behaviour is preserved rather than papered over.
 *
 * <p>Package-private: this is an implementation detail of the ISO-DEP layer and
 * nothing outside it should see a mutable frame.
 */
final class ReceiveBuffer {

    /** Matches {@code sizeof(res)} in the C. */
    static final int CAPACITY = 256;

    private final byte[] bytes = new byte[CAPACITY];
    private int length;

    /**
     * Zeroes the buffer, as the C's {@code memset(res, 0, sizeof(res))} does at
     * the top of {@code MFRC522_RATS} and
     * {@code MFRC522_14443P4_Transceive}.
     */
    void clear() {
        Arrays.fill(bytes, (byte) 0);
        length = 0;
    }

    /**
     * Copies what {@code MFRC522_ToCard} drained into offset zero, and sets the
     * length to the response's <em>bit-derived</em> byte count -- which is not
     * necessarily the number of bytes just copied.
     */
    void acceptCommandData(PcdResponse response) {
        response.copyInto(bytes);
        length = Math.min(response.byteCount(), CAPACITY);
    }

    /** Appends one byte at the current length. {@code p4_read_rx_fifo}. */
    void append(int value) {
        if (length < CAPACITY) {
            bytes[length++] = (byte) value;
        }
    }

    /** Shortens the frame, used when a CRC scan finds the real end. */
    void truncateTo(int newLength) {
        length = Math.max(0, Math.min(newLength, CAPACITY));
    }

    /** Current frame length in bytes. */
    int length() {
        return length;
    }

    /** True if the buffer is at its 256-byte limit. */
    boolean isFull() {
        return length >= CAPACITY;
    }

    /**
     * One byte, unsigned. Reads anywhere in the 256-byte array, including past
     * {@link #length()} -- see the class comment for why that is deliberate.
     */
    int byteAt(int index) {
        return bytes[index] & 0xFF;
    }

    /** The backing array, for CRC scanning. Not copied: the caller must not retain it. */
    byte[] array() {
        return bytes;
    }

    /** A copy of {@code count} bytes starting at {@code offset}. */
    byte[] copyRange(int offset, int count) {
        return Arrays.copyOfRange(bytes, offset, offset + count);
    }

    /** A copy of the current frame. */
    byte[] toByteArray() {
        return Arrays.copyOf(bytes, length);
    }
}
