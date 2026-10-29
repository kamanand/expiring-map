package com.expiringmap;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A type-safe heterogeneous expiring map.
 *
 * <p>Unlike a traditional homogeneous map where all entries must share the same value type,
 * this interface allows a single map instance to hold entries of completely different data types
 * (e.g., numbers, strings, custom domain records, collections) while maintaining full compile-time
 * type safety and eliminating unsafe casting.
 *
 * <p>All operations comply with the 3 core requirements:
 * <ul>
 *   <li>Configurable default TTL and optional active background sweeper.</li>
 *   <li>Per-entry TTL overrides with default fallback.</li>
 *   <li>Overriding existing keys always stores the latest value and refreshes the expiry.</li>
 * </ul>
 */
public interface HeterogeneousExpiringMap extends AutoCloseable {

    /**
     * Associates the specified value with the typed key using the map's default TTL.
     * Overwrites any existing value and refreshes the TTL.
     *
     * @param key typed key token
     * @param value value to associate
     * @param <T> value type
     * @return previous non-expired value associated with the key, or null
     */
    <T> T put(TypedKey<T> key, T value);

    /**
     * Associates the specified value with the typed key using a custom TTL.
     * Overwrites any existing value and refreshes the TTL with the specified duration.
     *
     * @param key typed key token
     * @param value value to associate
     * @param ttl custom time-to-live
     * @param <T> value type
     * @return previous non-expired value associated with the key, or null
     */
    <T> T put(TypedKey<T> key, T value, Duration ttl);

    /**
     * Associates the specified value with the typed key using a custom TTL.
     *
     * @param key typed key token
     * @param value value to associate
     * @param ttl custom time-to-live amount
     * @param unit time unit
     * @param <T> value type
     * @return previous non-expired value associated with the key, or null
     */
    default <T> T put(TypedKey<T> key, T value, long ttl, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("TimeUnit cannot be null");
        }
        return put(key, value, Duration.ofNanos(unit.toNanos(ttl)));
    }

    /**
     * Atomically associates the specified value with the key if not already present or expired.
     */
    <T> T putIfAbsent(TypedKey<T> key, T value);

    /**
     * Atomically associates the specified value with the key if not already present or expired, with custom TTL.
     */
    <T> T putIfAbsent(TypedKey<T> key, T value, Duration ttl);

    /**
     * Retrieves the latest unexpired value for the typed key, with compile-time type safety.
     *
     * @param key typed key token
     * @param <T> value type
     * @return latest unexpired value, or null if absent or expired
     */
    <T> T get(TypedKey<T> key);

    /**
     * Checks whether an unexpired mapping exists for this typed key.
     *
     * @param key typed key token
     * @return true if present and unexpired
     */
    boolean containsKey(TypedKey<?> key);

    /**
     * Removes the mapping for this typed key.
     *
     * @param key typed key token
     * @param <T> value type
     * @return removed value, or null if absent or expired
     */
    <T> T remove(TypedKey<T> key);

    /**
     * Returns remaining TTL duration for the specified typed key.
     */
    Optional<Duration> getRemainingTtl(TypedKey<?> key);

    /**
     * Resets expiration for an existing typed key.
     */
    boolean resetExpiration(TypedKey<?> key);

    /**
     * Resets expiration for an existing typed key to a new TTL.
     */
    boolean resetExpiration(TypedKey<?> key, Duration newTtl);

    /**
     * Manually triggers cleanup of expired entries.
     */
    void cleanUp();

    /**
     * Returns number of unexpired entries.
     */
    int size();

    /**
     * Returns true if there are no unexpired entries.
     */
    boolean isEmpty();

    /**
     * Clears all entries.
     */
    void clear();

    /**
     * Returns configured default TTL.
     */
    Duration getDefaultTtl();

    /**
     * Returns the underlying ExpiringMap.
     */
    ExpiringMap<TypedKey<?>, Object> asMap();

    @Override
    default void close() {
        asMap().close();
    }

    /**
     * Creates a new HeterogeneousExpiringMap with the specified default TTL.
     */
    static HeterogeneousExpiringMap create(Duration defaultTtl) {
        return new ConcurrentHeterogeneousExpiringMap(
                ExpiringMap.<TypedKey<?>, Object>builder(defaultTtl).build()
        );
    }

    /**
     * Creates a new HeterogeneousExpiringMap wrapping the provided ExpiringMap.
     */
    static HeterogeneousExpiringMap create(ExpiringMap<TypedKey<?>, Object> underlying) {
        return new ConcurrentHeterogeneousExpiringMap(underlying);
    }
}
