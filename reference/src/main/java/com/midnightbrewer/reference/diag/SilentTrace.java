package com.midnightbrewer.reference.diag;

import java.util.function.Supplier;

/**
 * The null object behind {@link ProtocolTrace#none()}.
 *
 * <p>Reporting {@code isEnabled() == false} is the load-bearing part: callers
 * that would otherwise hex-format a 64 byte frame on every exchange skip the
 * work entirely.
 */
final class SilentTrace implements ProtocolTrace {

    static final SilentTrace INSTANCE = new SilentTrace();

    private SilentTrace() {
    }

    @Override
    public void log(String message) {
        // Deliberately empty.
    }

    @Override
    public void log(Supplier<String> message) {
        // Deliberately empty: never evaluates the supplier.
    }

    @Override
    public void frame(String label, byte[] data, int length) {
        // Deliberately empty.
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
