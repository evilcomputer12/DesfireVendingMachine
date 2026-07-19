package com.midnightbrewer.reference.pcd;

/**
 * The two hardware timeout settings the C driver switches between, expressed as
 * {@code TReloadRegH}/{@code TReloadRegL} pairs.
 *
 * <p>The RC522's timer runs at
 * {@code f_timer = 13.56 MHz / (2 * TPrescaler + 1)}. With the
 * {@code TPrescaler = 0x0D3E = 3390} that {@code MFRC522_Init} programs, that
 * is roughly 2 kHz, so one tick is about 0.5 ms and the timeout is
 * {@code (TReload + 1) * 0.5 ms}.
 *
 * <p>Making this an enum rather than two functions puts the two magic pairs
 * side by side with the arithmetic that produced them, and stops the
 * ISO 14443-4 layer from needing to know any of it: it asks for
 * {@link #NV_WRITE} and gets the right register values.
 */
public enum ResponseTimeout {

    /**
     * About 300 ms -- {@code TReload = 2 * 256 + 88 = 600}.
     *
     * <p>The C's comment records why it is not smaller: DESFire NV-write
     * operations (CreateApplication, ChangeKey, WriteData) can take 50-100 ms,
     * and an earlier 15 ms setting was too short.
     */
    DEFAULT(2, 88),

    /**
     * About 2 s -- {@code TReload = 15 * 256 + 159 = 3999}.
     *
     * <p>Installed for the duration of every ISO 14443-4 APDU exchange, because
     * a DESFire committing a key change or formatting the PICC can hold the
     * line far past the default. {@code MFRC522_14443P4_Transceive} sets this on
     * entry and restores {@link #DEFAULT} on exit, whether or not the exchange
     * succeeded.
     */
    NV_WRITE(15, 159);

    private final int reloadHigh;
    private final int reloadLow;

    ResponseTimeout(int reloadHigh, int reloadLow) {
        this.reloadHigh = reloadHigh;
        this.reloadLow = reloadLow;
    }

    /** Value for {@code TReloadRegH}. */
    public int reloadHigh() {
        return reloadHigh;
    }

    /** Value for {@code TReloadRegL}. */
    public int reloadLow() {
        return reloadLow;
    }

    /** The configured timeout in milliseconds, at the standard prescaler. */
    public int approximateMillis() {
        return ((reloadHigh * 256 + reloadLow) + 1) / 2;
    }
}
