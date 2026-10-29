package com.expiringmap;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe implementation of {@link HeterogeneousExpiringMap} backed by an {@link ExpiringMap}.
 */
public class ConcurrentHeterogeneousExpiringMap implements HeterogeneousExpiringMap {

    private final ExpiringMap<TypedKey<?>, Object> underlying;

    public ConcurrentHeterogeneousExpiringMap(ExpiringMap<TypedKey<?>, Object> underlying) {
        this.underlying = Objects.requireNonNull(underlying, "underlying ExpiringMap cannot be null");
    }

    @Override
    public <T> T put(TypedKey<T> key, T value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Object previous = underlying.put(key, value);
        return key.cast(previous);
    }

    @Override
    public <T> T put(TypedKey<T> key, T value, Duration ttl) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Object previous = underlying.put(key, value, ttl);
        return key.cast(previous);
    }

    @Override
    public <T> T putIfAbsent(TypedKey<T> key, T value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Object previous = underlying.putIfAbsent(key, value);
        return key.cast(previous);
    }

    @Override
    public <T> T putIfAbsent(TypedKey<T> key, T value, Duration ttl) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Object previous = underlying.putIfAbsent(key, value, ttl);
        return key.cast(previous);
    }

    @Override
    public <T> T get(TypedKey<T> key) {
        Objects.requireNonNull(key, "key cannot be null");
        Object val = underlying.get(key);
        return key.cast(val);
    }

    @Override
    public boolean containsKey(TypedKey<?> key) {
        Objects.requireNonNull(key, "key cannot be null");
        return underlying.containsKey(key);
    }

    @Override
    public <T> T remove(TypedKey<T> key) {
        Objects.requireNonNull(key, "key cannot be null");
        Object removed = underlying.remove(key);
        return key.cast(removed);
    }

    @Override
    public Optional<Duration> getRemainingTtl(TypedKey<?> key) {
        Objects.requireNonNull(key, "key cannot be null");
        return underlying.getRemainingTtl(key);
    }

    @Override
    public boolean resetExpiration(TypedKey<?> key) {
        Objects.requireNonNull(key, "key cannot be null");
        return underlying.resetExpiration(key);
    }

    @Override
    public boolean resetExpiration(TypedKey<?> key, Duration newTtl) {
        Objects.requireNonNull(key, "key cannot be null");
        return underlying.resetExpiration(key, newTtl);
    }

    @Override
    public void cleanUp() {
        underlying.cleanUp();
    }

    @Override
    public int size() {
        return underlying.size();
    }

    @Override
    public boolean isEmpty() {
        return underlying.isEmpty();
    }

    @Override
    public void clear() {
        underlying.clear();
    }

    @Override
    public Duration getDefaultTtl() {
        return underlying.getDefaultTtl();
    }

    @Override
    public ExpiringMap<TypedKey<?>, Object> asMap() {
        return underlying;
    }

    @Override
    public String toString() {
        return underlying.toString();
    }
}
