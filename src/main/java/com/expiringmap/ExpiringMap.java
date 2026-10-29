package com.expiringmap;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe, configurable key-value map where entries expire after a specified time-to-live (TTL).
 *
 * <p>Key features:
 * <ul>
 *   <li><b>Configurable Expiry:</b> Each map instance has a default TTL configured at build time.</li>
 *   <li><b>Per-Entry TTL Override:</b> Users can specify a custom TTL when inserting an entry;
 *       otherwise, the entry falls back to the map's default TTL.</li>
 *   <li><b>Value Override &amp; Freshness:</b> Overwriting an existing key always stores the latest
 *       inserted value and refreshes its expiration deadline. {@link #get(Object)} always returns
 *       the most recently inserted value (if unexpired).</li>
 *   <li><b>Concurrency Safe:</b> All operations are thread-safe and non-blocking for reads,
 *       avoiding lock contention using atomic compare-and-swap semantics.</li>
 * </ul>
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 */
public interface ExpiringMap<K, V> extends ConcurrentMap<K, V>, AutoCloseable {

    /**
     * Associates the specified value with the specified key using the map's default TTL.
     * If the map previously contained a mapping for the key, the old value is replaced with
     * the new value and its expiration countdown is reset.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with key, or {@code null} if there was no non-expired mapping
     * @throws NullPointerException if key or value is null
     */
    @Override
    V put(K key, V value);

    /**
     * Associates the specified value with the specified key using a custom time-to-live duration.
     * If the map previously contained a mapping for the key, the old value is replaced with
     * the new value and its expiration deadline is computed from the specified TTL.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param ttl the time-to-live duration for this entry (must be positive)
     * @return the previous value associated with key, or {@code null} if there was no non-expired mapping
     * @throws NullPointerException if key, value, or ttl is null
     * @throws IllegalArgumentException if ttl is zero or negative
     */
    V put(K key, V value, Duration ttl);

    /**
     * Associates the specified value with the specified key using a custom time-to-live.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param ttl the time-to-live amount
     * @param unit the time unit of the ttl argument
     * @return the previous value associated with key, or {@code null} if there was no non-expired mapping
     * @throws NullPointerException if key, value, or unit is null
     * @throws IllegalArgumentException if ttl is {@code <= 0}
     */
    default V put(K key, V value, long ttl, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("TimeUnit cannot be null");
        }
        return put(key, value, Duration.ofNanos(unit.toNanos(ttl)));
    }

    /**
     * Associates the specified value with the specified key, expiring at a specific future instant.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param expireAt the specific future instant at which this entry expires
     * @return the previous value associated with key, or {@code null} if there was no non-expired mapping
     * @throws NullPointerException if key, value, or expireAt is null
     * @throws IllegalArgumentException if expireAt is in the past or now
     */
    V putUntil(K key, V value, java.time.Instant expireAt);

    /**
     * If the specified key is not already associated with a non-expired value,
     * associates it with the given value using the specified custom TTL.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param ttl the custom time-to-live duration
     * @return the current (existing) non-expired value associated with the key, or {@code null} if none existed
     */
    V putIfAbsent(K key, V value, Duration ttl);

    /**
     * If the specified key is not already associated with a non-expired value,
     * associates it with the given value using the specified custom TTL.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param ttl the custom time-to-live amount
     * @param unit the time unit of the ttl argument
     * @return the current (existing) non-expired value associated with the key, or {@code null} if none existed
     */
    default V putIfAbsent(K key, V value, long ttl, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("TimeUnit cannot be null");
        }
        return putIfAbsent(key, value, Duration.ofNanos(unit.toNanos(ttl)));
    }

    /**
     * Retrieves detailed metadata about an entry, including createdAt, expiresAt, and remaining lifetime.
     *
     * @param key the key whose entry details are desired
     * @return an {@link Optional} containing the entry details, or empty if the key is absent or expired
     */
    Optional<ExpiringEntry<K, V>> getEntry(K key);

    /**
     * Returns the remaining time-to-live for the specified key.
     *
     * @param key the key whose remaining TTL is queried
     * @return an {@link Optional} containing the remaining Duration, or empty if absent or expired
     */
    Optional<Duration> getRemainingTtl(K key);

    /**
     * Resets the expiration countdown of an existing unexpired key back to its configured TTL.
     *
     * @param key the key whose expiration timer should be reset
     * @return {@code true} if the entry existed and was reset, {@code false} if absent or already expired
     */
    boolean resetExpiration(K key);

    /**
     * Resets the expiration countdown of an existing unexpired key to a new TTL duration.
     *
     * @param key the key whose expiration timer should be updated
     * @param newTtl the new time-to-live duration
     * @return {@code true} if the entry existed and was updated, {@code false} if absent or already expired
     */
    boolean resetExpiration(K key, Duration newTtl);

    /**
     * Manually triggers an active cleanup of all expired entries.
     */
    void cleanUp();

    /**
     * Returns the configured default TTL for entries where no custom TTL was provided.
     *
     * @return the default TTL Duration
     */
    Duration getDefaultTtl();

    /**
     * Closes the map and releases any resources (e.g. background cleanup executor).
     */
    @Override
    default void close() {
        // Default implementation for instances without background cleanup
    }

    /**
     * Creates a new fluent builder for configuring and building an {@link ExpiringMap}.
     *
     * @param <K> key type
     * @param <V> value type
     * @return a new builder
     */
    static <K, V> ExpiringMapBuilder<K, V> builder() {
        return new ExpiringMapBuilder<>();
    }

    /**
     * Creates a new fluent builder initialized with the given default TTL.
     *
     * @param defaultTtl default time-to-live for entries
     * @param <K> key type
     * @param <V> value type
     * @return a new builder
     */
    static <K, V> ExpiringMapBuilder<K, V> builder(Duration defaultTtl) {
        return new ExpiringMapBuilder<K, V>().defaultTtl(defaultTtl);
    }

    /**
     * Creates a builder specifically for constructing a type-safe {@link HeterogeneousExpiringMap}.
     *
     * @return a builder configured for heterogeneous key-values
     */
    static ExpiringMapBuilder<TypedKey<?>, Object> heterogeneousBuilder() {
        return new ExpiringMapBuilder<>();
    }

    /**
     * Creates a new default {@link HeterogeneousExpiringMap} with 10-minute default TTL.
     */
    static HeterogeneousExpiringMap createHeterogeneous() {
        return HeterogeneousExpiringMap.create(Duration.ofMinutes(10));
    }

    /**
     * Creates a new {@link HeterogeneousExpiringMap} with the specified default TTL.
     */
    static HeterogeneousExpiringMap createHeterogeneous(Duration defaultTtl) {
        return HeterogeneousExpiringMap.create(defaultTtl);
    }
}
