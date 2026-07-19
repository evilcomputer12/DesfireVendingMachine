package com.midnightbrewer.reference.error;

import com.midnightbrewer.reference.desfire.DesfireStatus;
import com.midnightbrewer.reference.util.Hex;

/**
 * A DESFire command returned a status byte other than success.
 *
 * <p>This is the card refusing or failing a command it understood: a wrong key
 * ({@code 0xAE}), a debit past the lower limit ({@code 0xBE}), a command the
 * current state does not allow ({@code 0x9D}). It carries the raw status byte
 * so a caller can branch on a specific one -- the wallet demo, for instance,
 * treats {@code 0xA0 APPLICATION_NOT_FOUND} from SelectApplication as "this
 * card needs provisioning" rather than as a failure.
 *
 * <p>A {@link ProtocolException} would be wrong here: the framing was correct
 * and the card answered cleanly. The disagreement is about what the command
 * was allowed to do, which is a card-state problem, so this extends
 * {@link CardException}.
 */
public final class DesfireStatusException extends CardException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final int command;

    public DesfireStatusException(int command, int status) {
        super("DESFire command " + Hex.byteToString(command) + " failed: "
                + DesfireStatus.describe(status));
        this.command = command & 0xFF;
        this.status = status & 0xFF;
    }

    /** The DESFire status byte the card returned. */
    public int status() {
        return status;
    }

    /** The command byte that produced it. */
    public int command() {
        return command;
    }

    /** True if the status matches {@code expected}. */
    public boolean is(DesfireStatus expected) {
        return expected != null && expected.code() == status;
    }
}
