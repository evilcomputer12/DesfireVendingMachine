package com.midnightbrewer.reference;

import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.iso14443.ActivatedCard;
import com.midnightbrewer.reference.iso14443.FrameSize;
import com.midnightbrewer.reference.iso14443.Iso14443Transceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An {@link Iso14443Transceiver} decorator that times every APDU and remembers
 * what it saw.
 *
 * <p>A decorator, not a change to the transceiver: it forwards every call to
 * the wrapped link and adds nothing to the wire. {@link DesfireApduChannel} and
 * everything above it are unaware it is there, which is the point -- the timing
 * of a real transaction can be measured without threading a stopwatch through
 * the command code. Only {@link #transceive(byte[])} is instrumented, because
 * that is the only call that crosses the RF gap during the wallet sequence.
 *
 * <p>The command name is recovered from the wrapped APDU's second byte, the
 * DESFire command inside the {@code 90 cmd 00 00 ...} ISO 7816 envelope, so the
 * report reads in DESFire terms rather than as raw hex.
 */
final class TimingTransceiver implements Iso14443Transceiver {

    private final Iso14443Transceiver delegate;
    private final List<Exchange> exchanges = new ArrayList<>();

    TimingTransceiver(Iso14443Transceiver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public byte[] transceive(byte[] apdu) throws NfcException {
        long start = System.nanoTime();
        byte[] response = delegate.transceive(apdu);
        long elapsedNanos = System.nanoTime() - start;
        int command = apdu.length > 1 ? apdu[1] & 0xFF : -1;
        exchanges.add(new Exchange(exchanges.size() + 1, command,
                apdu.length, response == null ? 0 : response.length, elapsedNanos));
        return response;
    }

    /** Every timed exchange, in order. */
    List<Exchange> exchanges() {
        return List.copyOf(exchanges);
    }

    /** Total time spent inside {@link #transceive}, in milliseconds. */
    double totalMillis() {
        long nanos = 0;
        for (Exchange exchange : exchanges) {
            nanos += exchange.elapsedNanos;
        }
        return nanos / 1_000_000.0;
    }

    // ---- forwarded, untimed ------------------------------------------------

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

    /** One timed APDU round-trip. */
    static final class Exchange {
        private final int sequence;
        private final int command;
        private final int requestBytes;
        private final int responseBytes;
        private final long elapsedNanos;

        Exchange(int sequence, int command, int requestBytes, int responseBytes, long elapsedNanos) {
            this.sequence = sequence;
            this.command = command;
            this.requestBytes = requestBytes;
            this.responseBytes = responseBytes;
            this.elapsedNanos = elapsedNanos;
        }

        int sequence() {
            return sequence;
        }

        int requestBytes() {
            return requestBytes;
        }

        int responseBytes() {
            return responseBytes;
        }

        double millis() {
            return elapsedNanos / 1_000_000.0;
        }

        /** A readable name for the DESFire command byte, or its hex if unknown. */
        String commandName() {
            switch (command) {
                case 0x60: return "GetVersion";
                case 0x5A: return "SelectApplication";
                case 0x45: return "GetKeySettings";
                case 0xAF: return "AdditionalFrame";
                case 0x0A: return "AuthenticateLegacy";
                case 0x1A: return "AuthenticateISO";
                case 0x71: return "AuthenticateEV2First";
                case 0xCA: return "CreateApplication";
                case 0xC4: return "ChangeKey";
                case 0xCC: return "CreateValueFile";
                case 0x6C: return "GetValue";
                case 0x0C: return "Credit";
                case 0xDC: return "Debit";
                case 0x1C: return "LimitedCredit";
                case 0xC7: return "CommitTransaction";
                case 0xA7: return "AbortTransaction";
                default:   return command < 0 ? "?" : String.format("0x%02X", command);
            }
        }
    }
}
