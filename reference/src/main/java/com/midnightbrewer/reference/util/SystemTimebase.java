package com.midnightbrewer.reference.util;

/**
 * The production {@link Timebase}: real sleeps against the JVM's monotonic clock.
 *
 * <p>{@link System#nanoTime()} rather than {@code currentTimeMillis()} because
 * every use here is a duration, and a wall-clock jump mid-transaction (NTP on a
 * Pi that has just found the network) would otherwise abort a card exchange.
 *
 * <p>Package-private and reached through {@link Timebase#system()} -- there is
 * no reason for calling code to name the implementation.
 */
final class SystemTimebase implements Timebase {

    static final SystemTimebase INSTANCE = new SystemTimebase();

    private SystemTimebase() {
    }

    @Override
    public void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Preserve the interrupt for whoever owns the thread; a swallowed
            // interrupt here would make a polling loop unstoppable.
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public long millis() {
        return System.nanoTime() / 1_000_000L;
    }
}
