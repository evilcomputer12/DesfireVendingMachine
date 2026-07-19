package com.midnightbrewer.reference.util;

/**
 * The driver's sense of time: the seam that stands in for {@code HAL_Delay} and
 * {@code HAL_GetTick}.
 *
 * <p>Several of the ported timings are long -- the FIFO settle loop budgets
 * three seconds, and a card asking for eight waiting-time extensions costs
 * over half a second of sleeping. Tests that actually slept those out would be
 * useless, so every delay in this module goes through this interface and the
 * test suite substitutes a virtual clock.
 *
 * <p>Injecting it also documents an easy-to-miss property of the C: its delays
 * are wall-clock busy-waits on a single-threaded MCU. Making time an explicit
 * collaborator keeps that assumption visible instead of scattering
 * {@code Thread.sleep} through the protocol code.
 */
public interface Timebase {

    /**
     * Blocks for at least {@code millis} milliseconds. The port of
     * {@code HAL_Delay}.
     */
    void sleep(long millis);

    /**
     * A monotonically increasing millisecond counter. The port of
     * {@code HAL_GetTick}. Only differences between two readings are
     * meaningful; the origin is arbitrary.
     */
    long millis();

    /** The real clock, backed by {@link Thread#sleep} and {@link System#nanoTime}. */
    static Timebase system() {
        return SystemTimebase.INSTANCE;
    }
}
