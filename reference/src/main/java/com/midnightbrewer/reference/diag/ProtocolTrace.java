package com.midnightbrewer.reference.diag;

import com.midnightbrewer.reference.util.Hex;

import java.io.PrintStream;
import java.util.function.Supplier;

/**
 * Where the driver reports what it is doing on the air.
 *
 * <p>{@code RC522.c} calls {@code printf} from inside the protocol code, which
 * means the tracing cannot be turned off at runtime, cannot be redirected, and
 * has to be compiled out with {@code #if RC522_VERBOSE}. Here it is an
 * injected collaborator instead, so the same protocol objects can run silently
 * in tests, verbosely during hardware bring-up, and into a log file in a
 * kiosk -- with no change to the protocol code and no static state.
 *
 * <p>{@link #none()} is a null object rather than a null reference: the driver
 * never has to check whether tracing is enabled.
 */
@FunctionalInterface
public interface ProtocolTrace {

    /**
     * Records one line. Implementations must not throw -- a broken log must
     * never break a card transaction.
     */
    void log(String message);

    /**
     * Records a line only if this trace is enabled, without building the string
     * otherwise. Frame dumps are the expensive case, and they sit on the hot
     * path of every exchange.
     */
    default void log(Supplier<String> message) {
        if (isEnabled()) {
            log(message.get());
        }
    }

    /** Records a labelled frame as {@code "label (Nb) AA BB CC"}. */
    default void frame(String label, byte[] data, int length) {
        if (isEnabled()) {
            log(label + " (" + length + "b) " + Hex.encode(data, 0, length));
        }
    }

    /** False for {@link #none()}, letting callers skip formatting work. */
    default boolean isEnabled() {
        return true;
    }

    /** Discards everything. The default for library use and for tests. */
    static ProtocolTrace none() {
        return SilentTrace.INSTANCE;
    }

    /** Writes to standard output, the way the C firmware does. */
    static ProtocolTrace toStdout() {
        return to(System.out);
    }

    /** Writes to any stream, one line per call. */
    static ProtocolTrace to(PrintStream stream) {
        return message -> stream.println("[P4] " + message);
    }
}
