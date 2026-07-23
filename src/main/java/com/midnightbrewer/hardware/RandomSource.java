package com.midnightbrewer.hardware;

import java.security.SecureRandom;

/**
 * A source of random bytes -- the seam that makes the auth handshake testable.
 *
 * The real card path uses {@link #secure()} (true randomness). A test injects
 * a fake that returns a FIXED RndA, so the whole handshake becomes a
 * known-answer test against the replay vectors. Same trick as SpiLink /
 * FakeSpiLink: depend on an interface, substitute a fake in tests.
 *
 * It's a @FunctionalInterface, so a fake can be a one-line lambda:
 *     RandomSource fixed = n -> myFixedBytes;
 */
@FunctionalInterface
public interface RandomSource {

    /** Return {@code n} random bytes. */
    byte[] nextBytes(int n);

    /** The real one, backed by a cryptographically secure RNG. */
    static RandomSource secure() {
        SecureRandom rng = new SecureRandom();
        return n -> {
            byte[] out = new byte[n];
            rng.nextBytes(out);
            return out;
        };
    }
}
