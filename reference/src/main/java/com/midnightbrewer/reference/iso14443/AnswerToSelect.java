package com.midnightbrewer.reference.iso14443;

import com.midnightbrewer.reference.util.Hex;

import java.util.Arrays;

/**
 * The ATS: what a card returns from RATS, and where the frame size comes from.
 *
 * <p>Layout is {@code TL T0 [TA1] [TB1] [TC1] [historical bytes]}. TL is the
 * total length, and T0 carries the interface bytes' presence flags in bits 6..4
 * and the frame size index (FSCI) in bits 3..0.
 *
 * <p><strong>The frame size index is read from the wrong nibble, deliberately.</strong>
 * The C computes it as {@code (res[1] >> 4) & 0x0F} -- the <em>high</em> nibble
 * of T0, which by the specification holds the presence flags, not FSCI. For a
 * typical DESFire ATS of {@code 06 75 77 81 02 80} that yields index 7 (FSC 128)
 * where the correct reading of the low nibble would give index 5 (FSC 64).
 *
 * <p>It is reproduced verbatim, and it is harmless in this driver, because the
 * transmit path caps INF at 40 bytes regardless -- see
 * {@code Rc522IsoDepTransceiver.MAX_TRANSMIT_INFORMATION_BYTES}. The overstated
 * FSC never reaches the air. {@link #specificationFrameSize()} exposes the
 * correct reading alongside it so the difference can be seen rather than
 * guessed at, but nothing in the driver uses it. Changing the default would
 * lift the send limit above what the card agreed to, which is precisely the
 * kind of change that turns a working reader into an intermittent one.
 */
public final class AnswerToSelect {

    private final byte[] bytes;

    private AnswerToSelect(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * Wraps the first {@code length} bytes of a received RATS reply.
     *
     * <p>The C accepts anything two bytes or longer -- it only ever indexes
     * {@code res[1]} -- so no stricter validation is applied here.
     */
    public static AnswerToSelect parse(byte[] frame, int length) {
        if (length < 2) {
            throw new IllegalArgumentException("ATS needs at least TL and T0, got " + length);
        }
        return new AnswerToSelect(Arrays.copyOf(frame, length));
    }

    /** A copy of the raw ATS as received. */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /** Number of bytes received. */
    public int length() {
        return bytes.length;
    }

    /** TL, the length byte the card reports. May disagree with {@link #length()}. */
    public int declaredLength() {
        return bytes[0] & 0xFF;
    }

    /** T0, the format byte. */
    public int formatByte() {
        return bytes[1] & 0xFF;
    }

    /**
     * The frame size index as the C reads it: the high nibble of T0. See the
     * class comment -- this is not what the specification says, and it is
     * intentional.
     */
    public int frameSizeIndex() {
        return (formatByte() >> 4) & 0x0F;
    }

    /** The frame size the driver will actually use, from {@link #frameSizeIndex()}. */
    public FrameSize frameSize() {
        return FrameSize.fromIndex(frameSizeIndex());
    }

    /**
     * The frame size a specification-conformant reader would derive, from the
     * low nibble of T0.
     *
     * <p>Diagnostic only. Nothing in the driver calls it; it exists so that the
     * discrepancy is visible in a log next to the value actually in use.
     */
    public FrameSize specificationFrameSize() {
        return FrameSize.fromIndex(formatByte() & 0x0F);
    }

    @Override
    public String toString() {
        return Hex.encode(bytes) + " [" + frameSize()
                + ", spec would read " + specificationFrameSize() + "]";
    }
}
