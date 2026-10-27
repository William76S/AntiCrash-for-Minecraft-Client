package dev.servercrashguard;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded counter, not one main-thread task or chat message per packet. */
public final class GuardNotifications {
    public record Notice(long count, String reason) {}
    private final AtomicLong count = new AtomicLong();
    private final AtomicReference<String> reason = new AtomicReference<>("unknown");
    private long lastEmission;
    private boolean emitted;

    public void add(String value) {
        reason.set(value);
        count.incrementAndGet();
    }

    /** Call on the client main thread. */
    public Notice poll(long now) {
        if (emitted && now - lastEmission < 2_000_000_000L) return null;
        long pending = count.getAndSet(0);
        if (pending == 0) return null;
        lastEmission = now;
        emitted = true;
        return new Notice(pending, reason.get());
    }
}
