package com.expiringmap;

/**
 * Functional callback invoked when an entry expires and is removed from the {@link ExpiringMap}.
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 */
@FunctionalInterface
public interface ExpirationListener<K, V> {

    /**
     * Invoked when an entry has expired.
     *
     * @param key the expired key
     * @param value the expired value
     */
    void onExpired(K key, V value);
}
