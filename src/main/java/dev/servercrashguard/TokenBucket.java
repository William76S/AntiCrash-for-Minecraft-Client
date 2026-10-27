package dev.servercrashguard;

import java.util.function.LongSupplier;

/** Monotonic token bucket. A boundary cannot reset the entire budget twice. */
public final class TokenBucket {
    private final double capacity;
    private final LongSupplier clock;
    private double tokens;
    private long last;

    public TokenBucket(int capacity, LongSupplier clock) {
        this.capacity = capacity;
        this.clock = clock;
        tokens = capacity;
        last = clock.getAsLong();
    }

    public boolean take(int cost) {
        long now = clock.getAsLong();
        long elapsed = now - last;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * (capacity / 1_000_000_000.0));
            last = now;
        }
        if (cost < 0 || cost > tokens) return false;
        tokens -= cost;
        return true;
    }
}
