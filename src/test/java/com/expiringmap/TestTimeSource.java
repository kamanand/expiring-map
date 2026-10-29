package com.expiringmap;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic, controllable {@link TimeSource} for fast unit tests.
 * Allows simulating passage of time without Thread.sleep.
 */
public class TestTimeSource implements TimeSource {

    private final AtomicLong currentNanos;
    private final AtomicLong currentMillis;

    public TestTimeSource() {
        this(1_000_000_000L, 1_700_000_000_000L);
    }

    public TestTimeSource(long initialNanos, long initialMillis) {
        this.currentNanos = new AtomicLong(initialNanos);
        this.currentMillis = new AtomicLong(initialMillis);
    }

    @Override
    public long nanoTime() {
        return currentNanos.get();
    }

    @Override
    public long currentTimeMillis() {
        return currentMillis.get();
    }

    public void advance(Duration duration) {
        long nanos = duration.toNanos();
        currentNanos.addAndGet(nanos);
        currentMillis.addAndGet(nanos / 1_000_000L);
    }

    public void advance(long amount, TimeUnit unit) {
        advance(Duration.ofNanos(unit.toNanos(amount)));
    }
}
