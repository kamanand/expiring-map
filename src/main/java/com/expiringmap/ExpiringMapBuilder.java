package com.expiringmap;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Fluent builder for creating configured {@link ExpiringMap} instances.
 *
 * <p>Example usage:
 * <pre>{@code
 * ExpiringMap<String, UserSession> sessionMap = ExpiringMap.<String, UserSession>builder()
 *     .defaultTtl(Duration.ofMinutes(15))
 *     .cleanUpInterval(Duration.ofMinutes(1))
 *     .expirationListener((key, session) -> System.out.println("Session expired: " + key))
 *     .build();
 * }</pre>
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
public final class ExpiringMapBuilder<K, V> {

    private Duration defaultTtl = Duration.ofMinutes(10);
    private Duration cleanUpInterval = null; // null means passive/lazy cleanup only
    private TimeSource timeSource = TimeSource.SYSTEM;
    private ExpirationListener<K, V> expirationListener = null;
    private int maxCapacity = 0; // 0 or negative means unbounded
    private String threadNamePrefix = "ExpiringMap-Cleaner";

    ExpiringMapBuilder() {
    }

    /**
     * Sets the default time-to-live for entries when no custom TTL is specified during insertion.
     *
     * @param defaultTtl the default TTL Duration (must be positive)
     * @return this builder
     */
    public ExpiringMapBuilder<K, V> defaultTtl(Duration defaultTtl) {
        Objects.requireNonNull(defaultTtl, "defaultTtl cannot be null");
        if (defaultTtl.isZero() || defaultTtl.isNegative()) {
            throw new IllegalArgumentException("defaultTtl must be positive, got: " + defaultTtl);
        }
        this.defaultTtl = defaultTtl;
        return this;
    }

    /**
     * Sets the default time-to-live for entries when no custom TTL is specified during insertion.
     *
     * @param duration amount of time
     * @param unit the time unit
     * @return this builder
     */
    public ExpiringMapBuilder<K, V> defaultTtl(long duration, TimeUnit unit) {
        Objects.requireNonNull(unit, "TimeUnit cannot be null");
        if (duration <= 0) {
            throw new IllegalArgumentException("duration must be positive, got: " + duration);
        }
        return defaultTtl(Duration.ofNanos(unit.toNanos(duration)));
    }

    /**
     * Sets the interval for periodic active background cleanup sweeps.
     * When set, a dedicated daemon thread will periodically evict expired entries
     * and notify registered expiration listeners, even if the entries are never accessed again.
     *
     * @param cleanUpInterval the sweep interval (must be positive)
     * @return this builder
     */
    public ExpiringMapBuilder<K, V> cleanUpInterval(Duration cleanUpInterval) {
        if (cleanUpInterval != null && (cleanUpInterval.isZero() || cleanUpInterval.isNegative())) {
            throw new IllegalArgumentException("cleanUpInterval must be positive, got: " + cleanUpInterval);
        }
        this.cleanUpInterval = cleanUpInterval;
        return this;
    }

    /**
     * Sets the interval for periodic active background cleanup sweeps.
     *
     * @param interval amount of time
     * @param unit the time unit
     * @return this builder
     */
    public ExpiringMapBuilder<K, V> cleanUpInterval(long interval, TimeUnit unit) {
        Objects.requireNonNull(unit, "TimeUnit cannot be null");
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive, got: " + interval);
        }
        return cleanUpInterval(Duration.ofNanos(unit.toNanos(interval)));
    }

    /**
     * Configures a custom {@link TimeSource}. Useful for deterministic, instantaneous unit tests.
     * Defaults to {@link TimeSource#SYSTEM}.
     *
     * @param timeSource custom time source
     * @return this builder
     */
    public ExpiringMapBuilder<K, V> timeSource(TimeSource timeSource) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource cannot be null");
        return this;
    }

    /**
     * Registers a listener to be notified when an entry expires and is removed.
     *
     * @param listener the listener callback
     * @return this builder
     */
    public ExpiringMapBuilder<K, V> expirationListener(ExpirationListener<K, V> listener) {
        this.expirationListener = listener;
        return this;
    }

    /**
     * Sets a maximum capacity for the map. If the capacity is exceeded, expired entries
     * are purged first, followed by oldest inserted entries if still over capacity.
     *
     * @param maxCapacity maximum number of entries ({@code <= 0} for unbounded)
     * @return this builder
     */
    public ExpiringMapBuilder<K, V> maxCapacity(int maxCapacity) {
        this.maxCapacity = Math.max(0, maxCapacity);
        return this;
    }

    /**
     * Sets thread name prefix for the background cleanup scheduler thread (if enabled).
     *
     * @param prefix the thread name prefix
     * @return this builder
     */
    public ExpiringMapBuilder<K, V> threadNamePrefix(String prefix) {
        this.threadNamePrefix = Objects.requireNonNull(prefix, "prefix cannot be null");
        return this;
    }

    /**
     * Builds and returns a new {@link ExpiringMap} instance with the configured settings.
     *
     * @return a new ExpiringMap
     */
    public ExpiringMap<K, V> build() {
        return new ConcurrentExpiringMap<>(
                defaultTtl,
                cleanUpInterval,
                timeSource,
                expirationListener,
                maxCapacity,
                threadNamePrefix
        );
    }

    /**
     * Builds and returns a type-safe {@link HeterogeneousExpiringMap} allowing mixed value types
     * indexed by {@link TypedKey}.
     *
     * @return a new HeterogeneousExpiringMap
     */
    @SuppressWarnings("unchecked")
    public HeterogeneousExpiringMap buildHeterogeneous() {
        ExpiringMap<TypedKey<?>, Object> typedMap = (ExpiringMap<TypedKey<?>, Object>) (ExpiringMap<?, ?>) build();
        return new ConcurrentHeterogeneousExpiringMap(typedMap);
    }
}
