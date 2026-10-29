package com.expiringmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MapContractTest {

    private TestTimeSource timeSource;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
    }

    @Test
    @DisplayName("Contract: computeIfAbsent creates value when absent or expired")
    void testComputeIfAbsent() {
        ExpiringMap<String, Integer> map = ExpiringMap.<String, Integer>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        // 1. Initial compute
        Integer val1 = map.computeIfAbsent("hits", k -> 1);
        assertThat(val1).isEqualTo(1);
        assertThat(map.get("hits")).isEqualTo(1);

        // 2. Already present & unexpired -> function not executed
        Integer val2 = map.computeIfAbsent("hits", k -> 99);
        assertThat(val2).isEqualTo(1);

        // 3. Let it expire
        timeSource.advance(Duration.ofSeconds(6));

        // Now computeIfAbsent treats expired as absent and recalculates
        Integer val3 = map.computeIfAbsent("hits", k -> 42);
        assertThat(val3).isEqualTo(42);
        assertThat(map.get("hits")).isEqualTo(42);
    }

    @Test
    @DisplayName("Contract: computeIfPresent updates existing unexpired entry")
    void testComputeIfPresent() {
        ExpiringMap<String, Integer> map = ExpiringMap.<String, Integer>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        map.put("score", 10);

        // Update when present
        Integer updated = map.computeIfPresent("score", (k, v) -> v + 5);
        assertThat(updated).isEqualTo(15);
        assertThat(map.get("score")).isEqualTo(15);

        // Return null removes entry
        Integer removed = map.computeIfPresent("score", (k, v) -> null);
        assertThat(removed).isNull();
        assertThat(map.containsKey("score")).isFalse();

        // Expired entry should not be updated
        map.put("score", 50);
        timeSource.advance(Duration.ofSeconds(6));
        Integer afterExpiry = map.computeIfPresent("score", (k, v) -> v + 10);
        assertThat(afterExpiry).isNull();
        assertThat(map.get("score")).isNull();
    }

    @Test
    @DisplayName("Contract: compute updates existing, inserts if absent, or deletes if null")
    void testCompute() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        // Compute absent
        map.compute("user", (k, v) -> "initial");
        assertThat(map.get("user")).isEqualTo("initial");

        // Compute existing
        map.compute("user", (k, v) -> v + "-modified");
        assertThat(map.get("user")).isEqualTo("initial-modified");

        // Expire and compute (should receive null as old value)
        timeSource.advance(Duration.ofSeconds(6));
        map.compute("user", (k, v) -> {
            assertThat(v).isNull();
            return "revived";
        });
        assertThat(map.get("user")).isEqualTo("revived");
    }

    @Test
    @DisplayName("Contract: merge combines values or sets initial value")
    void testMerge() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        map.merge("log", "line1\n", (oldV, newV) -> oldV + newV);
        map.merge("log", "line2\n", (oldV, newV) -> oldV + newV);

        assertThat(map.get("log")).isEqualTo("line1\nline2\n");
    }

    @Test
    @DisplayName("Contract: replace with expected oldValue")
    void testReplace() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        map.put("key", "val1");

        // Mismatched old value
        assertThat(map.replace("key", "wrong", "val2")).isFalse();
        assertThat(map.get("key")).isEqualTo("val1");

        // Matching old value
        assertThat(map.replace("key", "val1", "val2")).isTrue();
        assertThat(map.get("key")).isEqualTo("val2");

        // Expired entry cannot be replaced
        timeSource.advance(Duration.ofSeconds(6));
        assertThat(map.replace("key", "val2", "val3")).isFalse();
    }

    @Test
    @DisplayName("Contract: putAll bulk insertion")
    void testPutAll() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        map.putAll(Map.of("a", "1", "b", "2", "c", "3"));
        assertThat(map.size()).isEqualTo(3);
        assertThat(map.get("a")).isEqualTo("1");
        assertThat(map.get("b")).isEqualTo("2");
        assertThat(map.get("c")).isEqualTo("3");
    }
}
