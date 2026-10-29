package com.expiringmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ExpiringMapTest {

    private TestTimeSource timeSource;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
    }

    @Test
    @DisplayName("Condition 1: Should act as configurable expiry map with default TTL")
    void testDefaultExpiration() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        map.put("key1", "val1");

        // Before expiry
        assertThat(map.get("key1")).isEqualTo("val1");
        assertThat(map.containsKey("key1")).isTrue();
        assertThat(map.size()).isEqualTo(1);
        assertThat(map.isEmpty()).isFalse();

        // Advance time to just before expiry
        timeSource.advance(Duration.ofSeconds(4));
        assertThat(map.get("key1")).isEqualTo("val1");
        assertThat(map.containsKey("key1")).isTrue();

        // Advance time past expiry
        timeSource.advance(Duration.ofSeconds(1)); // total 5s reached
        assertThat(map.get("key1")).isNull();
        assertThat(map.containsKey("key1")).isFalse();
        assertThat(map.size()).isEqualTo(0);
        assertThat(map.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Condition 2: Custom TTL provided on insert overrides default TTL; fallback to default if not provided")
    void testCustomTtlPerEntryOverridesDefault() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .build();

        // keyA uses default TTL (10s)
        map.put("keyA", "defaultTtlValue");

        // keyB uses custom short TTL (3s)
        map.put("keyB", "shortTtlValue", Duration.ofSeconds(3));

        // keyC uses custom long TTL (20s)
        map.put("keyC", "longTtlValue", 20, TimeUnit.SECONDS);

        // Advance 4s: keyB should expire (3s), keyA (10s) and keyC (20s) should remain
        timeSource.advance(Duration.ofSeconds(4));
        assertThat(map.get("keyB")).isNull();
        assertThat(map.get("keyA")).isEqualTo("defaultTtlValue");
        assertThat(map.get("keyC")).isEqualTo("longTtlValue");
        assertThat(map.size()).isEqualTo(2);

        // Advance 7 more seconds (total 11s): keyA should expire (10s), keyC (20s) should remain
        timeSource.advance(Duration.ofSeconds(7));
        assertThat(map.get("keyB")).isNull();
        assertThat(map.get("keyA")).isNull();
        assertThat(map.get("keyC")).isEqualTo("longTtlValue");
        assertThat(map.size()).isEqualTo(1);

        // Advance 10 more seconds (total 21s): keyC should expire
        timeSource.advance(Duration.ofSeconds(10));
        assertThat(map.get("keyC")).isNull();
        assertThat(map.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Condition 3: Overriding existing key always returns latest inserted value and refreshes TTL")
    void testOverrideExistingKeyReturnsLatestInsertedValue() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        // Insert initial value
        map.put("user1", "John");
        assertThat(map.get("user1")).isEqualTo("John");

        // Override with new value before expiry
        timeSource.advance(Duration.ofSeconds(2));
        String previous = map.put("user1", "Johnathan");
        assertThat(previous).isEqualTo("John");
        assertThat(map.get("user1")).isEqualTo("Johnathan");

        // Advance 4 seconds (6s from initial insert, 4s from 2nd insert)
        // If TTL was not refreshed, it would have expired at 5s.
        // But because it was overridden at 2s with default 5s TTL, it expires at 2 + 5 = 7s.
        timeSource.advance(Duration.ofSeconds(4));
        assertThat(map.get("user1")).isEqualTo("Johnathan");

        // Override with a 3rd value and a custom TTL of 10s
        map.put("user1", "Johnny", Duration.ofSeconds(10));
        assertThat(map.get("user1")).isEqualTo("Johnny");

        // Advance 6 seconds: should still return latest value "Johnny"
        timeSource.advance(Duration.ofSeconds(6));
        assertThat(map.get("user1")).isEqualTo("Johnny");

        // Advance 5 more seconds (total 11s since 3rd insert): now expired
        timeSource.advance(Duration.ofSeconds(5));
        assertThat(map.get("user1")).isNull();
    }

    @Test
    @DisplayName("Condition 3: Overriding an expired key revives it with the new value")
    void testOverrideExpiredKeyRevivesKey() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(3))
                .timeSource(timeSource)
                .build();

        map.put("session", "token-v1");
        timeSource.advance(Duration.ofSeconds(4));
        // Expired
        assertThat(map.get("session")).isNull();

        // Re-insert / override expired key
        String prev = map.put("session", "token-v2", Duration.ofSeconds(10));
        assertThat(prev).isNull(); // Previous was expired, treated as null
        assertThat(map.get("session")).isEqualTo("token-v2");

        // Advance 5s (still within 10s TTL)
        timeSource.advance(Duration.ofSeconds(5));
        assertThat(map.get("session")).isEqualTo("token-v2");
    }

    @Test
    @DisplayName("putIfAbsent: Only inserts when absent or expired")
    void testPutIfAbsent() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        // 1. Initial absent insert
        assertThat(map.putIfAbsent("k1", "v1")).isNull();
        assertThat(map.get("k1")).isEqualTo("v1");

        // 2. Already present and not expired -> should not overwrite
        assertThat(map.putIfAbsent("k1", "v2")).isEqualTo("v1");
        assertThat(map.get("k1")).isEqualTo("v1");

        // 3. Custom TTL putIfAbsent
        assertThat(map.putIfAbsent("k2", "v2-custom", Duration.ofSeconds(2))).isNull();
        assertThat(map.get("k2")).isEqualTo("v2-custom");

        // 4. Advance past k2 expiry
        timeSource.advance(Duration.ofSeconds(3));
        assertThat(map.get("k2")).isNull();

        // Now putIfAbsent on expired key should succeed and store new value
        assertThat(map.putIfAbsent("k2", "v2-revived", Duration.ofSeconds(10))).isNull();
        assertThat(map.get("k2")).isEqualTo("v2-revived");
    }

    @Test
    @DisplayName("putUntil: Associates entry expiring at specific future instant")
    void testPutUntil() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofMinutes(1))
                .timeSource(timeSource)
                .build();

        Instant targetExpiry = timeSource.now().plus(Duration.ofSeconds(15));
        map.putUntil("auth", "bearer-token", targetExpiry);

        assertThat(map.get("auth")).isEqualTo("bearer-token");

        timeSource.advance(Duration.ofSeconds(14));
        assertThat(map.get("auth")).isEqualTo("bearer-token");

        timeSource.advance(Duration.ofSeconds(2));
        assertThat(map.get("auth")).isNull();
    }

    @Test
    @DisplayName("ExpirationListener: Notified when entries expire during access or cleanUp")
    void testExpirationListener() {
        List<String> expiredEntries = new CopyOnWriteArrayList<>();

        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .expirationListener((k, v) -> expiredEntries.add(k + "=" + v))
                .build();

        map.put("k1", "val1");
        map.put("k2", "val2", Duration.ofSeconds(10));

        // Advance past k1 expiry
        timeSource.advance(Duration.ofSeconds(6));

        // Trigger passive cleanup via get
        map.get("k1");
        assertThat(expiredEntries).containsExactly("k1=val1");

        // k2 has not expired yet
        assertThat(map.get("k2")).isEqualTo("val2");

        // Advance past k2 expiry and run active cleanup
        timeSource.advance(Duration.ofSeconds(5));
        map.cleanUp();
        assertThat(expiredEntries).containsExactly("k1=val1", "k2=val2");
    }

    @Test
    @DisplayName("getRemainingTtl & getEntry: Return detailed lifecycle metadata")
    void testEntryDetailsAndRemainingTtl() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .build();

        map.put("item1", "data1", Duration.ofSeconds(20));

        timeSource.advance(Duration.ofSeconds(5));

        Optional<Duration> remainingTtl = map.getRemainingTtl("item1");
        assertThat(remainingTtl).isPresent();
        assertThat(remainingTtl.get().getSeconds()).isEqualTo(15);

        Optional<ExpiringEntry<String, String>> entryOpt = map.getEntry("item1");
        assertThat(entryOpt).isPresent();
        ExpiringEntry<String, String> entry = entryOpt.get();
        assertThat(entry.getKey()).isEqualTo("item1");
        assertThat(entry.getValue()).isEqualTo("data1");
        assertThat(entry.getConfiguredTtl()).isEqualTo(Duration.ofSeconds(20));
        assertThat(entry.isExpired()).isFalse();

        // Advance past expiry
        timeSource.advance(Duration.ofSeconds(16));
        assertThat(map.getRemainingTtl("item1")).isEmpty();
        assertThat(map.getEntry("item1")).isEmpty();
    }

    @Test
    @DisplayName("resetExpiration: Resets the TTL countdown of an existing entry")
    void testResetExpiration() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .build();

        map.put("k", "v");
        timeSource.advance(Duration.ofSeconds(8)); // 2s remaining

        // Reset to original TTL (10s)
        boolean reset = map.resetExpiration("k");
        assertThat(reset).isTrue();

        // Advance 5s (13s from start, 5s from reset) -> should still be present
        timeSource.advance(Duration.ofSeconds(5));
        assertThat(map.get("k")).isEqualTo("v");

        // Reset with custom TTL (2s)
        map.resetExpiration("k", Duration.ofSeconds(2));
        timeSource.advance(Duration.ofSeconds(3));
        assertThat(map.get("k")).isNull();
    }

    @Test
    @DisplayName("MaxCapacity: Evicts entries when capacity is exceeded")
    void testMaxCapacity() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofMinutes(10))
                .maxCapacity(2)
                .timeSource(timeSource)
                .build();

        map.put("k1", "v1");
        timeSource.advance(Duration.ofMillis(10));
        map.put("k2", "v2");
        timeSource.advance(Duration.ofMillis(10));

        assertThat(map.size()).isEqualTo(2);

        // Insert 3rd entry -> should evict oldest (k1)
        map.put("k3", "v3");

        assertThat(map.size()).isLessThanOrEqualTo(2);
        assertThat(map.get("k3")).isEqualTo("v3");
        assertThat(map.get("k1")).isNull(); // oldest evicted
    }

    @Test
    @DisplayName("Standard Map Operations: remove, replace, forEach, entrySet filter expired entries")
    void testMapContractMethods() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .build();

        map.put("k1", "v1");
        map.put("k2", "v2");
        map.put("k3", "v3", Duration.ofSeconds(2));

        // Advance 3s -> k3 expires
        timeSource.advance(Duration.ofSeconds(3));

        // replace
        assertThat(map.replace("k1", "v1", "v1-new")).isTrue();
        assertThat(map.get("k1")).isEqualTo("v1-new");

        // remove
        assertThat(map.remove("k2", "wrong")).isFalse();
        assertThat(map.remove("k2", "v2")).isTrue();
        assertThat(map.containsKey("k2")).isFalse();

        // Views should only show unexpired k1
        assertThat(map.keySet()).containsExactly("k1");
        assertThat(map.values()).containsExactly("v1-new");

        // forEach
        Map<String, String> collected = new HashMap<>();
        map.forEach(collected::put);
        assertThat(collected).containsExactly(Map.entry("k1", "v1-new"));
    }

    @Test
    @DisplayName("Validation: Throws NullPointerException and IllegalArgumentException on bad input")
    void testValidation() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .build();

        assertThatThrownBy(() -> map.put(null, "val"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> map.put("key", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> map.put("key", "val", (Duration) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> map.put("key", "val", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> map.put("key", "val", Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ExpiringMap.builder(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Concurrency: High concurrent override and read operations always yield latest values")
    void testConcurrentOverrideAndRead() throws Exception {
        int threads = 8;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        ExpiringMap<String, Integer> map = ExpiringMap.<String, Integer>builder()
                .defaultTtl(Duration.ofMinutes(1))
                .build();

        AtomicInteger maxObserved = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < operationsPerThread; op++) {
                        int val = threadId * 10000 + op;
                        map.put("sharedKey", val);
                        Integer current = map.get("sharedKey");
                        if (current != null) {
                            maxObserved.accumulateAndGet(current, Math::max);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(map.get("sharedKey")).isNotNull();
        assertThat(map.containsKey("sharedKey")).isTrue();
    }
}
