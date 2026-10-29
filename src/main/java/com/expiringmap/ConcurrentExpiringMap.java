package com.expiringmap;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * High-performance, thread-safe implementation of {@link ExpiringMap}.
 *
 * <p>Uses fine-grained lock-free and atomic CAS operations on top of {@link ConcurrentHashMap}
 * to ensure that:
 * <ul>
 *   <li>Reads are completely non-blocking.</li>
 *   <li>Overriding existing keys always stores the latest inserted value and refreshes the TTL.</li>
 *   <li>Concurrent expirations never accidentally delete newly overridden values.</li>
 *   <li>Passive expiration occurs on access; optional active background cleanup periodically sweeps expired entries.</li>
 * </ul>
 *
 * @param <K> key type
 * @param <V> value type
 */
public class ConcurrentExpiringMap<K, V> implements ExpiringMap<K, V> {

    private final ConcurrentHashMap<K, TimestampedValue<V>> storage;
    private final Duration defaultTtl;
    private final TimeSource timeSource;
    private final ExpirationListener<K, V> expirationListener;
    private final int maxCapacity;
    private final ScheduledExecutorService cleanupExecutor;
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    /**
     * Internal wrapper storing value, creation timestamp, deadline, and TTL.
     */
    private static final class TimestampedValue<V> {
        final V value;
        final long createdNanos;
        final long expiresAtNanos;
        final long createdAtMillis;
        final Duration configuredTtl;
        final long sequence;

        TimestampedValue(V value,
                         long createdNanos,
                         long expiresAtNanos,
                         long createdAtMillis,
                         Duration configuredTtl,
                         long sequence) {
            this.value = value;
            this.createdNanos = createdNanos;
            this.expiresAtNanos = expiresAtNanos;
            this.createdAtMillis = createdAtMillis;
            this.configuredTtl = configuredTtl;
            this.sequence = sequence;
        }

        /**
         * Checks expiration using monotonic nano difference to safely handle potential overflows.
         */
        boolean isExpired(long currentNanoTime) {
            return (currentNanoTime - expiresAtNanos) >= 0;
        }
    }

    public ConcurrentExpiringMap(Duration defaultTtl,
                                 Duration cleanUpInterval,
                                 TimeSource timeSource,
                                 ExpirationListener<K, V> expirationListener,
                                 int maxCapacity,
                                 String threadNamePrefix) {
        this.defaultTtl = Objects.requireNonNull(defaultTtl, "defaultTtl cannot be null");
        if (defaultTtl.isZero() || defaultTtl.isNegative()) {
            throw new IllegalArgumentException("defaultTtl must be positive, got: " + defaultTtl);
        }
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource cannot be null");
        this.expirationListener = expirationListener;
        this.maxCapacity = Math.max(0, maxCapacity);
        this.storage = new ConcurrentHashMap<>();

        if (cleanUpInterval != null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, threadNamePrefix + "-" + System.identityHashCode(this));
                t.setDaemon(true);
                return t;
            });
            executor.setRemoveOnCancelPolicy(true);
            long intervalNanos = cleanUpInterval.toNanos();
            executor.scheduleAtFixedRate(this::cleanUp, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
            this.cleanupExecutor = executor;
        } else {
            this.cleanupExecutor = null;
        }
    }

    @Override
    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    @Override
    public V put(K key, V value) {
        return put(key, value, defaultTtl);
    }

    @Override
    public V put(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(ttl, "ttl cannot be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got: " + ttl);
        }

        long nowNanos = timeSource.nanoTime();
        long nowMillis = timeSource.currentTimeMillis();
        long expiresAtNanos = computeDeadline(nowNanos, ttl);
        long seq = sequenceGenerator.incrementAndGet();

        TimestampedValue<V> newValue = new TimestampedValue<>(value, nowNanos, expiresAtNanos, nowMillis, ttl, seq);
        TimestampedValue<V> prev = storage.put(key, newValue);

        checkCapacity();

        if (prev == null || prev.isExpired(nowNanos)) {
            if (prev != null) {
                notifyExpired(key, prev.value);
            }
            return null;
        }
        return prev.value;
    }

    @Override
    public V putUntil(K key, V value, Instant expireAt) {
        Objects.requireNonNull(expireAt, "expireAt cannot be null");
        Instant now = timeSource.now();
        Duration ttl = Duration.between(now, expireAt);
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("expireAt must be in the future, got: " + expireAt + " (current time: " + now + ")");
        }
        return put(key, value, ttl);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return putIfAbsent(key, value, defaultTtl);
    }

    @Override
    public V putIfAbsent(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(ttl, "ttl cannot be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got: " + ttl);
        }

        long nowNanos = timeSource.nanoTime();
        long nowMillis = timeSource.currentTimeMillis();
        long expiresAtNanos = computeDeadline(nowNanos, ttl);
        long seq = sequenceGenerator.incrementAndGet();

        TimestampedValue<V> newValue = new TimestampedValue<>(value, nowNanos, expiresAtNanos, nowMillis, ttl, seq);
        AtomicReference<V> previousValidValue = new AtomicReference<>();
        AtomicReference<V> expiredValue = new AtomicReference<>();

        storage.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(nowNanos)) {
                if (existing != null) {
                    expiredValue.set(existing.value);
                }
                previousValidValue.set(null);
                return newValue;
            } else {
                previousValidValue.set(existing.value);
                return existing;
            }
        });

        if (expiredValue.get() != null) {
            notifyExpired(key, expiredValue.get());
        }

        if (previousValidValue.get() == null) {
            checkCapacity();
        }

        return previousValidValue.get();
    }

    @Override
    public V get(Object key) {
        Objects.requireNonNull(key, "key cannot be null");
        TimestampedValue<V> entry = storage.get(key);
        if (entry == null) {
            return null;
        }
        long nowNanos = timeSource.nanoTime();
        if (entry.isExpired(nowNanos)) {
            // Lazy atomic cleanup
            if (storage.remove(key, entry)) {
                @SuppressWarnings("unchecked")
                K typedKey = (K) key;
                notifyExpired(typedKey, entry.value);
            }
            return null;
        }
        return entry.value;
    }

    @Override
    public Optional<ExpiringEntry<K, V>> getEntry(K key) {
        Objects.requireNonNull(key, "key cannot be null");
        TimestampedValue<V> entry = storage.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        long nowNanos = timeSource.nanoTime();
        if (entry.isExpired(nowNanos)) {
            if (storage.remove(key, entry)) {
                notifyExpired(key, entry.value);
            }
            return Optional.empty();
        }

        long remainingNanos = Math.max(0, entry.expiresAtNanos - nowNanos);
        Instant createdAt = Instant.ofEpochMilli(entry.createdAtMillis);
        Instant expiresAt = createdAt.plus(entry.configuredTtl);

        return Optional.of(new ExpiringEntry<>(
                key,
                entry.value,
                createdAt,
                expiresAt,
                entry.configuredTtl,
                Duration.ofNanos(remainingNanos)
        ));
    }

    @Override
    public Optional<Duration> getRemainingTtl(K key) {
        Objects.requireNonNull(key, "key cannot be null");
        TimestampedValue<V> entry = storage.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        long nowNanos = timeSource.nanoTime();
        if (entry.isExpired(nowNanos)) {
            if (storage.remove(key, entry)) {
                notifyExpired(key, entry.value);
            }
            return Optional.empty();
        }
        long remainingNanos = Math.max(0, entry.expiresAtNanos - nowNanos);
        return Optional.of(Duration.ofNanos(remainingNanos));
    }

    @Override
    public boolean resetExpiration(K key) {
        return resetExpiration(key, null);
    }

    @Override
    public boolean resetExpiration(K key, Duration newTtl) {
        Objects.requireNonNull(key, "key cannot be null");
        if (newTtl != null && (newTtl.isZero() || newTtl.isNegative())) {
            throw new IllegalArgumentException("newTtl must be positive, got: " + newTtl);
        }

        long nowNanos = timeSource.nanoTime();
        long nowMillis = timeSource.currentTimeMillis();
        AtomicBoolean resetSuccess = new AtomicBoolean(false);
        AtomicReference<V> expiredValue = new AtomicReference<>();

        storage.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(nowNanos)) {
                expiredValue.set(existing.value);
                return null; // purge expired
            }
            Duration ttlToUse = (newTtl != null) ? newTtl : existing.configuredTtl;
            long deadline = computeDeadline(nowNanos, ttlToUse);
            resetSuccess.set(true);
            return new TimestampedValue<>(
                    existing.value,
                    nowNanos,
                    deadline,
                    nowMillis,
                    ttlToUse,
                    sequenceGenerator.incrementAndGet()
            );
        });

        if (expiredValue.get() != null) {
            notifyExpired(key, expiredValue.get());
        }

        return resetSuccess.get();
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        Objects.requireNonNull(value, "value cannot be null");
        long nowNanos = timeSource.nanoTime();
        for (Map.Entry<K, TimestampedValue<V>> entry : storage.entrySet()) {
            TimestampedValue<V> val = entry.getValue();
            if (val != null) {
                if (val.isExpired(nowNanos)) {
                    if (storage.remove(entry.getKey(), val)) {
                        notifyExpired(entry.getKey(), val.value);
                    }
                } else if (Objects.equals(val.value, value)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public V remove(Object key) {
        Objects.requireNonNull(key, "key cannot be null");
        TimestampedValue<V> removed = storage.remove(key);
        if (removed == null) {
            return null;
        }
        long nowNanos = timeSource.nanoTime();
        if (removed.isExpired(nowNanos)) {
            @SuppressWarnings("unchecked")
            K typedKey = (K) key;
            notifyExpired(typedKey, removed.value);
            return null;
        }
        return removed.value;
    }

    @Override
    public boolean remove(Object key, Object value) {
        Objects.requireNonNull(key, "key cannot be null");
        if (value == null) {
            return false;
        }
        TimestampedValue<V> existing = storage.get(key);
        if (existing == null) {
            return false;
        }
        long nowNanos = timeSource.nanoTime();
        if (existing.isExpired(nowNanos)) {
            if (storage.remove(key, existing)) {
                @SuppressWarnings("unchecked")
                K typedKey = (K) key;
                notifyExpired(typedKey, existing.value);
            }
            return false;
        }
        if (Objects.equals(existing.value, value)) {
            return storage.remove(key, existing);
        }
        return false;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(oldValue, "oldValue cannot be null");
        Objects.requireNonNull(newValue, "newValue cannot be null");

        long nowNanos = timeSource.nanoTime();
        long nowMillis = timeSource.currentTimeMillis();
        AtomicBoolean replaced = new AtomicBoolean(false);
        AtomicReference<V> expiredValue = new AtomicReference<>();

        storage.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(nowNanos)) {
                expiredValue.set(existing.value);
                return null; // purge
            }
            if (Objects.equals(existing.value, oldValue)) {
                replaced.set(true);
                long deadline = computeDeadline(nowNanos, existing.configuredTtl);
                return new TimestampedValue<>(
                        newValue,
                        nowNanos,
                        deadline,
                        nowMillis,
                        existing.configuredTtl,
                        sequenceGenerator.incrementAndGet()
                );
            }
            return existing;
        });

        if (expiredValue.get() != null) {
            notifyExpired(key, expiredValue.get());
        }

        return replaced.get();
    }

    @Override
    public V replace(K key, V value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");

        long nowNanos = timeSource.nanoTime();
        long nowMillis = timeSource.currentTimeMillis();
        AtomicReference<V> previousValue = new AtomicReference<>(null);
        AtomicReference<V> expiredValue = new AtomicReference<>(null);

        storage.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpired(nowNanos)) {
                expiredValue.set(existing.value);
                return null;
            }
            previousValue.set(existing.value);
            long deadline = computeDeadline(nowNanos, existing.configuredTtl);
            return new TimestampedValue<>(
                    value,
                    nowNanos,
                    deadline,
                    nowMillis,
                    existing.configuredTtl,
                    sequenceGenerator.incrementAndGet()
            );
        });

        if (expiredValue.get() != null) {
            notifyExpired(key, expiredValue.get());
        }

        return previousValue.get();
    }

    @Override
    public int size() {
        cleanUp();
        return storage.size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public void clear() {
        storage.clear();
    }

    @Override
    public void cleanUp() {
        long nowNanos = timeSource.nanoTime();
        for (Map.Entry<K, TimestampedValue<V>> entry : storage.entrySet()) {
            TimestampedValue<V> val = entry.getValue();
            if (val != null && val.isExpired(nowNanos)) {
                if (storage.remove(entry.getKey(), val)) {
                    notifyExpired(entry.getKey(), val.value);
                }
            }
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        Objects.requireNonNull(m, "map cannot be null");
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public Set<K> keySet() {
        cleanUp();
        return Collections.unmodifiableSet(getActiveMap().keySet());
    }

    @Override
    public Collection<V> values() {
        cleanUp();
        return Collections.unmodifiableCollection(getActiveMap().values());
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        cleanUp();
        return Collections.unmodifiableSet(getActiveMap().entrySet());
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action, "action cannot be null");
        long nowNanos = timeSource.nanoTime();
        storage.forEach((k, val) -> {
            if (val != null) {
                if (val.isExpired(nowNanos)) {
                    if (storage.remove(k, val)) {
                        notifyExpired(k, val.value);
                    }
                } else {
                    action.accept(k, val.value);
                }
            }
        });
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        return computeIfAbsent(key, mappingFunction, defaultTtl);
    }

    /**
     * Computes value if absent with a specific custom TTL.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction, Duration ttl) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(mappingFunction, "mappingFunction cannot be null");
        Objects.requireNonNull(ttl, "ttl cannot be null");

        long nowNanos = timeSource.nanoTime();
        TimestampedValue<V> existing = storage.get(key);
        if (existing != null && !existing.isExpired(nowNanos)) {
            return existing.value;
        }

        AtomicReference<V> expiredValue = new AtomicReference<>();
        AtomicReference<V> computedResult = new AtomicReference<>();

        storage.compute(key, (k, current) -> {
            if (current != null && !current.isExpired(nowNanos)) {
                computedResult.set(current.value);
                return current;
            }
            if (current != null) {
                expiredValue.set(current.value);
            }
            V computed = mappingFunction.apply(k);
            if (computed == null) {
                return null;
            }
            computedResult.set(computed);
            long nowMillis = timeSource.currentTimeMillis();
            long deadline = computeDeadline(nowNanos, ttl);
            return new TimestampedValue<>(
                    computed,
                    nowNanos,
                    deadline,
                    nowMillis,
                    ttl,
                    sequenceGenerator.incrementAndGet()
            );
        });

        if (expiredValue.get() != null) {
            notifyExpired(key, expiredValue.get());
        }

        checkCapacity();
        return computedResult.get();
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(remappingFunction, "remappingFunction cannot be null");

        long nowNanos = timeSource.nanoTime();
        AtomicReference<V> result = new AtomicReference<>(null);
        AtomicReference<V> expiredValue = new AtomicReference<>();

        storage.computeIfPresent(key, (k, current) -> {
            if (current.isExpired(nowNanos)) {
                expiredValue.set(current.value);
                return null;
            }
            V newValue = remappingFunction.apply(k, current.value);
            if (newValue == null) {
                return null;
            }
            result.set(newValue);
            long nowMillis = timeSource.currentTimeMillis();
            long deadline = computeDeadline(nowNanos, current.configuredTtl);
            return new TimestampedValue<>(
                    newValue,
                    nowNanos,
                    deadline,
                    nowMillis,
                    current.configuredTtl,
                    sequenceGenerator.incrementAndGet()
            );
        });

        if (expiredValue.get() != null) {
            notifyExpired(key, expiredValue.get());
        }

        return result.get();
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(remappingFunction, "remappingFunction cannot be null");

        long nowNanos = timeSource.nanoTime();
        AtomicReference<V> result = new AtomicReference<>(null);
        AtomicReference<V> expiredValue = new AtomicReference<>();

        storage.compute(key, (k, current) -> {
            V oldValue = null;
            Duration ttlToUse = defaultTtl;
            if (current != null) {
                if (current.isExpired(nowNanos)) {
                    expiredValue.set(current.value);
                } else {
                    oldValue = current.value;
                    ttlToUse = current.configuredTtl;
                }
            }
            V newValue = remappingFunction.apply(k, oldValue);
            if (newValue == null) {
                return null;
            }
            result.set(newValue);
            long nowMillis = timeSource.currentTimeMillis();
            long deadline = computeDeadline(nowNanos, ttlToUse);
            return new TimestampedValue<>(
                    newValue,
                    nowNanos,
                    deadline,
                    nowMillis,
                    ttlToUse,
                    sequenceGenerator.incrementAndGet()
            );
        });

        if (expiredValue.get() != null) {
            notifyExpired(key, expiredValue.get());
        }

        checkCapacity();
        return result.get();
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(remappingFunction, "remappingFunction cannot be null");

        return compute(key, (k, oldValue) -> oldValue == null ? value : remappingFunction.apply(oldValue, value));
    }

    @Override
    public void close() {
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private Map<K, V> getActiveMap() {
        long nowNanos = timeSource.nanoTime();
        Map<K, V> active = new LinkedHashMap<>();
        for (Map.Entry<K, TimestampedValue<V>> entry : storage.entrySet()) {
            TimestampedValue<V> val = entry.getValue();
            if (val != null) {
                if (val.isExpired(nowNanos)) {
                    if (storage.remove(entry.getKey(), val)) {
                        notifyExpired(entry.getKey(), val.value);
                    }
                } else {
                    active.put(entry.getKey(), val.value);
                }
            }
        }
        return active;
    }

    private long computeDeadline(long nowNanos, Duration ttl) {
        long ttlNanos = ttl.toNanos();
        if (ttlNanos <= 0) {
            return nowNanos;
        }
        long deadline = nowNanos + ttlNanos;
        // Check for positive overflow
        if (ttlNanos > 0 && deadline < nowNanos) {
            return Long.MAX_VALUE;
        }
        return deadline;
    }

    private void notifyExpired(K key, V value) {
        if (expirationListener != null) {
            try {
                expirationListener.onExpired(key, value);
            } catch (Throwable ignored) {
                // Prevent listener errors from corrupting map state
            }
        }
    }

    private void checkCapacity() {
        if (maxCapacity <= 0) {
            return;
        }
        if (storage.size() > maxCapacity) {
            // First sweep expired entries
            cleanUp();
            // If still over capacity, evict oldest by sequence
            while (storage.size() > maxCapacity) {
                K oldestKey = null;
                long lowestSeq = Long.MAX_VALUE;
                TimestampedValue<V> oldestVal = null;
                for (Map.Entry<K, TimestampedValue<V>> entry : storage.entrySet()) {
                    TimestampedValue<V> v = entry.getValue();
                    if (v != null && v.sequence < lowestSeq) {
                        lowestSeq = v.sequence;
                        oldestKey = entry.getKey();
                        oldestVal = v;
                    }
                }
                if (oldestKey != null && oldestVal != null) {
                    if (storage.remove(oldestKey, oldestVal)) {
                        // Evicted due to capacity
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    @Override
    public String toString() {
        return getActiveMap().toString();
    }
}
