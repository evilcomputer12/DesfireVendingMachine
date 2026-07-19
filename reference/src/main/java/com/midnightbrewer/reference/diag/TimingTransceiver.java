package com.midnightbrewer.reference.diag;

import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.iso14443.ActivatedCard;
import com.midnightbrewer.reference.iso14443.FrameSize;
import com.midnightbrewer.reference.iso14443.Iso14443Transceiver;
import com.midnightbrewer.reference.util.Hex;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An {@link Iso14443Transceiver} that measures how long each APDU takes and
 * remembers it, without the transceiver it wraps knowing anything about it.
 *
 * <p>This is the decorator pattern, and it is worth pausing on as an example.
 * It {@code implements Iso14443Transceiver}, so anything expecting a
 * transceiver accepts it. It also <em>holds</em> a transceiver and forwards
 * every call to it. The only method it adds behaviour to is
 * {@link #transceive(byte[])}, where it starts a clock, delegates, stops the
 * clock, and records the result.
 *
 * <p>The payoff is that not one line of the protocol code changed to get
 * per-APDU timing. The measurement is composed on from the outside. That is
 * the same move the whole project rests on -- program to the interface, and
 * new behaviour can be wrapped around the old rather than edited into it.
 */
public final class TimingTransceiver implements Iso14443Transceiver {

    /** One timed exchange. */
    public static final class Exchange {
        private final int sequence;
        private final int commandByte;
        private final int requestBytes;
        private final int responseBytes;
        private final long micros;

        Exchange(int sequence, int commandByte, int requestBytes,
                 int responseBytes, long micros) {
            this.sequence = sequence;
            this.commandByte = commandByte;
            this.requestBytes = requestBytes;
            this.responseBytes = responseBytes;
            this.micros = micros;
        }

        public int sequence() {
            return sequence;
        }

        public int commandByte() {
            return commandByte;
        }

        public String commandName() {
            return DesfireCommandNames.of(commandByte);
        }

        public int requestBytes() {
            return requestBytes;
        }

        public int responseBytes() {
            return responseBytes;
        }

        public double millis() {
            return micros / 1000.0;
        }
    }

    private final Iso14443Transceiver delegate;
    private final List<Exchange> exchanges = new ArrayList<>();

    public TimingTransceiver(Iso14443Transceiver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public byte[] transceive(byte[] apdu) throws NfcException {
        // A wrapped DESFire APDU is 90 INS 00 00 [Lc data] Le, so byte 1 is
        // the command. Read it before delegating, in case the delegate throws.
        int command = apdu.length >= 2 ? apdu[1] & 0xFF : -1;

        long start = System.nanoTime();
        byte[] response = delegate.transceive(apdu);
        long micros = (System.nanoTime() - start) / 1000L;

        exchanges.add(new Exchange(exchanges.size() + 1, command,
                apdu.length, response.length, micros));
        return response;
    }

    /** Everything recorded so far, in order. */
    public List<Exchange> exchanges() {
        return List.copyOf(exchanges);
    }

    /** Total time spent inside {@link #transceive}, in milliseconds. */
    public double totalMillis() {
        long micros = 0;
        for (Exchange e : exchanges) {
            micros += e.micros;
        }
        return micros / 1000.0;
    }

    public void reset() {
        exchanges.clear();
    }

    // ---- everything else is pure delegation ----------------------------

    @Override
    public boolean isCardPresent() throws NfcException {
        return delegate.isCardPresent();
    }

    @Override
    public ActivatedCard activate() throws NfcException {
        return delegate.activate();
    }

    @Override
    public void deselect() throws NfcException {
        delegate.deselect();
    }

    @Override
    public void resetField(long offMillis) throws NfcException {
        delegate.resetField(offMillis);
    }

    @Override
    public FrameSize frameSize() {
        return delegate.frameSize();
    }

    @Override
    public void close() throws NfcException {
        delegate.close();
    }

    /** A short hex preview, handy when logging a request live. */
    static String preview(byte[] data) {
        int n = Math.min(data.length, 8);
        return Hex.encode(data, 0, n) + (data.length > n ? " ..." : "");
    }
}
