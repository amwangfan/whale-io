package com.turboio.addon;

import java.util.Random;

/** Exponential backoff with jitter: 30 s * 2^attempts, capped at 15 min, jitter 0.8x-1.2x. */
final class WhaleBackoff {
    static final long BASE_MS = 30_000L, CAP_MS = 15L * 60_000L;
    private WhaleBackoff() {}
    /** Delay before retry number {@code attempts} (0 = first failure). */
    static long delayMs(int attempts, Random random) {
        long nominal = Math.min(CAP_MS, BASE_MS << Math.max(0, Math.min(attempts, 10)));
        double jitter = 0.8 + 0.4 * random.nextDouble();
        return Math.max(1000L, (long) (nominal * jitter));
    }
    static long maxPossibleMs() { return (long) (CAP_MS * 1.2); }
}
