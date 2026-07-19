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
 * <p><strong>The C reads the frame size index from the wrong nibble, and this
 * class does not reproduce that.</strong> {@code MFRC522_RATS} computes it as
 * {@code (res[1] >> 4) & 0x0F} -- the <em>high</em> nibble of T0, which by the
 * specification holds the interface-byte presence flags. FSCI is the low
 * nibble. On the EV3 tested here, ATS {@code 06 75 77 81 02 80 02 F0} gives
 * index 7 (FSC 128) by the C's reading, where the low nibble gives index 5
 * (FSC 64).
 *
 * <p>The C gets away with it because its transmit path caps INF at 40 bytes
 * regardless, and both readings exceed that, so the overstated figure never
 * reaches the air. But the error is not one-directional: an ATS with
 * {@code T0 = 0x05}, meaning no interface bytes follow, yields a high nibble of
 * 0 and therefore FSC 16, understating a true FSC of 64. Whether the bug
 * inflates or deflates the limit depends on the card.
 *
 * <p>So {@link #frameSize()} follows the specification, and
 * {@link #legacyFrameSize()} keeps the C's reading for comparison in traces.
 * Note which direction this moves in: for a DESFire it lowers the advertised
 * limit from 128 to 64, so it is the more conservative choice, not a licence
 * to send bigger frames. The 40-byte transmit cap still applies on top.
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

    /** FSCI: the low nibble of T0, per ISO 14443-4. */
    public int frameSizeIndex() {
        return formatByte() & 0x0F;
    }

    /** The frame size the driver uses, from {@link #frameSizeIndex()}. */
    public FrameSize frameSize() {
        return FrameSize.fromIndex(frameSizeIndex());
    }

    /**
     * The frame size the C would derive, from the high nibble of T0.
     *
     * <p>Diagnostic only, so a trace can show both figures side by side when
     * comparing this driver against the STM32 firmware. Nothing uses it to
     * decide what goes on the air.
     */
    public FrameSize legacyFrameSize() {
        return FrameSize.fromIndex((formatByte() >> 4) & 0x0F);
    }

    @Override
    public String toString() {
        return Hex.encode(bytes) + " [" + frameSize()
                + ", C firmware would read " + legacyFrameSize() + "]";
    }
}
