package com.midnightbrewer.reference.desfire;

import java.security.SecureRandom;

/**
 * The source of the reader's nonce (RndA) in an authentication.
 *
 * <p>An interface, not a hard-wired {@link SecureRandom}, for one reason: a
 * test needs to fix RndA to reproduce a known-answer handshake against a
 * simulated card, and production needs real entropy. Mirrors the
 * {@code df_random_fn} callback the C context carries, minus the portable
 * fallback PRNG -- on a JVM {@link SecureRandom} is always available, so there
 * is no reason to ship a weaker default.
 */
@FunctionalInterface
public interface RandomSource {

    /** Fills {@code buffer} with random bytes. */
    void nextBytes(byte[] buffer);

    /** The production source: a shared {@link SecureRandom}. */
    static RandomSource secure() {
        SecureRandom secureRandom = new SecureRandom();
        return secureRandom::nextBytes;
    }

    /** Returns {@code length} fresh random bytes. */
    default byte[] nextBytes(int length) {
        byte[] out = new byte[length];
        nextBytes(out);
        return out;
    }
}
