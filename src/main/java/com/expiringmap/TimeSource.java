package com.expiringmap;

import java.time.Instant;

/**
 * Abstraction for reading time.
 * Provides monotonic time for TTL calculations (immune to wall-clock shifts)
 * and epoch time for user-facing timestamps.
 * Enables deterministic, fast unit testing without real-time sleeps.
 */
public interface TimeSource {

    /**
     * Returns current monotonic time in nanoseconds.
     * Note: Two readings t1, t2 should be compared using (t2 - t1) to handle potential overflow safely.
     *
     * @return current monotonic time in nanoseconds
     */
    long nanoTime();

    /**
     * Returns current wall-clock time in milliseconds from the epoch.
     *
     * @return current epoch milliseconds
     */
    long currentTimeMillis();

    /**
     * Returns the current instant based on {@link #currentTimeMillis()}.
     *
     * @return current Instant
     */
    default Instant now() {
        return Instant.ofEpochMilli(currentTimeMillis());
    }

    /**
     * Default system time source using {@link System#nanoTime()} and {@link System#currentTimeMillis()}.
     */
    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "TimeSource.SYSTEM";
        }
    };
}
