package com.midnightbrewer.reference.support;

import com.midnightbrewer.reference.util.Timebase;

/**
 * A {@link Timebase} where time only moves when someone sleeps.
 *
 * <p>The ported driver waits a lot: up to three seconds for the FIFO to settle
 * on every frame, and 80 ms per unit of every waiting-time extension. Tests that
 * spent that time would take minutes and would be flaky on a loaded machine.
 * Here a sleep advances a counter and returns immediately, so the same code
 * paths run with the same arithmetic in microseconds.
 *
 * <p>It also records the total, which lets a test assert that a WTX of 3 caused
 * a 240 ms wait -- a property that would otherwise be invisible.
 */
public final class VirtualTimebase implements Timebase {

    private long now;
    private long totalSlept;
    private int sleepCount;

    @Override
    public void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        now += millis;
        totalSlept += millis;
        sleepCount++;
    }

    @Override
    public long millis() {
        return now;
    }

    /** Total virtual milliseconds slept since construction. */
    public long totalSlept() {
        return totalSlept;
    }

    /** Number of sleep calls, useful for spotting a loop that never ran. */
    public int sleepCount() {
        return sleepCount;
    }

    /** Forgets the accumulated totals without rewinding the clock. */
    public void resetCounters() {
        totalSlept = 0;
        sleepCount = 0;
    }
}
