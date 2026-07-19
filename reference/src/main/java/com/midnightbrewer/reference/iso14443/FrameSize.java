package com.midnightbrewer.reference.iso14443;

/**
 * FSC, the largest frame a card will accept, negotiated at RATS.
 *
 * <p>ISO 14443-4 Table 5 maps a 4-bit index (FSCI) onto a byte count. A frame
 * costs three bytes of overhead -- one PCB and two CRC -- so the usable payload
 * is {@code FSC - 3}, and an APDU longer than that has to be chained.
 *
 * <p>A value object rather than a bare {@code uint16_t}, because "64" on its own
 * has been the frame size, the payload size and the FIFO depth at different
 * points in this codebase, and mixing them up produces frames a card silently
 * drops.
 */
public final class FrameSize {

    /**
     * ISO 14443-4 Table 5. An FSCI past the end of this table means 256, which
     * is what the C's {@code iso_fsci_to_fsc} returns for anything {@code >= 9}.
     */
    private static final int[] FSC_BY_INDEX = {16, 24, 32, 40, 48, 64, 96, 128, 256};

    /**
     * The starting assumption before any RATS, and the value
     * {@code MFRC522_FieldReset} restores. ISO 14443-4 guarantees every card
     * accepts at least this much.
     */
    public static final FrameSize DEFAULT = new FrameSize(64);

    /**
     * Fallback payload size when FSC is too small for the 3-byte overhead. The
     * C writes {@code (g_iso_fsc > 3u) ? (g_iso_fsc - 3u) : 16u}; the 16 is a
     * guess that only applies to a nonsensical FSC, but it is reproduced so an
     * absurd ATS cannot produce a negative length.
     */
    private static final int FALLBACK_MAX_INFORMATION = 16;

    private final int frameBytes;

    private FrameSize(int frameBytes) {
        this.frameBytes = frameBytes;
    }

    /** Looks up FSC from the index carried in the ATS. {@code iso_fsci_to_fsc}. */
    public static FrameSize fromIndex(int fsci) {
        if (fsci < 0 || fsci >= FSC_BY_INDEX.length) {
            return new FrameSize(256);
        }
        return new FrameSize(FSC_BY_INDEX[fsci]);
    }

    /** Total frame size in bytes, including PCB and CRC. */
    public int frameBytes() {
        return frameBytes;
    }

    /**
     * Bytes of INF that fit in one frame: {@code FSC - 3}.
     *
     * <p>This is the card's limit. What the driver actually sends is the smaller
     * of this and its own transmit cap -- see
     * {@code Rc522IsoDepTransceiver.MAX_TRANSMIT_INFORMATION_BYTES}.
     */
    public int maxInformationBytes() {
        return frameBytes > 3 ? frameBytes - 3 : FALLBACK_MAX_INFORMATION;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FrameSize size && size.frameBytes == frameBytes;
    }

    @Override
    public int hashCode() {
        return frameBytes;
    }

    @Override
    public String toString() {
        return "FSC=" + frameBytes + " (maxINF=" + maxInformationBytes() + ")";
    }
}
