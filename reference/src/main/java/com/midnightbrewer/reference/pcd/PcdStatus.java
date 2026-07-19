package com.midnightbrewer.reference.pcd;

/**
 * The three outcomes of one RC522 command, ported from {@code MI_OK},
 * {@code MI_NOTAGERR} and {@code MI_ERR}.
 *
 * <p>These survive the port as a low-level detail rather than becoming
 * exceptions immediately, because the ISO 14443-4 layer branches on them
 * repeatedly -- retrying, chaining, extending a timeout -- and a failure that
 * is about to be retried is not exceptional. The translation to the
 * {@link com.midnightbrewer.reference.error.NfcException} hierarchy happens at
 * the boundary where a failure becomes final.
 */
public enum PcdStatus {

    /** The command completed with no error bits set. {@code MI_OK}. */
    OK,

    /**
     * The hardware timer expired with no reply: no card, or the card left the
     * field. {@code MI_NOTAGERR}.
     */
    NO_TAG,

    /**
     * The command failed: a buffer overflow, a collision, a CRC error, a
     * protocol error, or the software backstop firing. {@code MI_ERR}.
     */
    ERROR;

    /** True only for {@link #OK}, for readability at the many call sites. */
    public boolean isOk() {
        return this == OK;
    }
}
