package com.midnightbrewer.hardware;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A fake {@link ApduChannel} for tests. It replays canned card replies in the
 * order you queue them, and records every APDU it was asked to send -- so a
 * test can both feed the handshake and inspect what the card would have seen.
 *
 * This is the "fake pipe" that lets you test DesfireCard with no reader and no
 * card, exactly like FakeSpiLink did for the driver.
 */
class FakeApduChannel implements ApduChannel {

    private final Deque<byte[]> replies = new ArrayDeque<>();

    /** Everything the card-under-test sent, in order. */
    final List<byte[]> sent = new ArrayList<>();

    /** Queue the next reply the fake card will hand back. */
    void queueReply(byte[] reply) {
        replies.add(reply);
    }

    @Override
    public byte[] transceive(byte[] apdu) {
        sent.add(apdu.clone());
        return replies.poll();
    }
}
