package com.expiringmap;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * An immutable view of an entry in the {@link ExpiringMap}, including its value,
 * creation/update instant, expiration instant, configured TTL, and remaining lifetime.
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
public final class ExpiringEntry<K, V> {

    private final K key;
    private final V value;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Duration configuredTtl;
    private final Duration remainingTtl;

    public ExpiringEntry(K key,
                         V value,
                         Instant createdAt,
                         Instant expiresAt,
                         Duration configuredTtl,
                         Duration remainingTtl) {
        this.key = Objects.requireNonNull(key, "key cannot be null");
        this.value = Objects.requireNonNull(value, "value cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
        this.configuredTtl = Objects.requireNonNull(configuredTtl, "configuredTtl cannot be null");
        this.remainingTtl = Objects.requireNonNull(remainingTtl, "remainingTtl cannot be null");
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Duration getConfiguredTtl() {
        return configuredTtl;
    }

    public Duration getRemainingTtl() {
        return remainingTtl;
    }

    public boolean isExpired() {
        return remainingTtl.isZero() || remainingTtl.isNegative();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpiringEntry)) return false;
        ExpiringEntry<?, ?> that = (ExpiringEntry<?, ?>) o;
        return Objects.equals(key, that.key) &&
               Objects.equals(value, that.value) &&
               Objects.equals(createdAt, that.createdAt) &&
               Objects.equals(expiresAt, that.expiresAt) &&
               Objects.equals(configuredTtl, that.configuredTtl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value, createdAt, expiresAt, configuredTtl);
    }

    @Override
    public String toString() {
        return "ExpiringEntry{" +
                "key=" + key +
                ", value=" + value +
                ", createdAt=" + createdAt +
                ", expiresAt=" + expiresAt +
                ", configuredTtl=" + configuredTtl +
                ", remainingTtl=" + remainingTtl +
                '}';
    }
}
