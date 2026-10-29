package com.expiringmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class BoundaryConditionsTest {

    private TestTimeSource timeSource;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
    }

    @Test
    @DisplayName("Boundary: Extremely large TTL does not overflow monotonic arithmetic")
    void testExtremelyLargeTtl() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofDays(365 * 100)) // 100 years
                .timeSource(timeSource)
                .build();

        map.put("perpetual", "data");

        // Advance 50 years
        timeSource.advance(Duration.ofDays(365 * 50));
        assertThat(map.get("perpetual")).isEqualTo("data");
        assertThat(map.containsKey("perpetual")).isTrue();

        Optional<Duration> remaining = map.getRemainingTtl("perpetual");
        assertThat(remaining).isPresent();
        assertThat(remaining.get().toDays()).isGreaterThan(365 * 40);
    }

    @Test
    @DisplayName("Boundary: Minimal 1-nanosecond TTL")
    void testSubMillisecondTtl() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofNanos(10))
                .timeSource(timeSource)
                .build();

        map.put("nanoKey", "nanoVal");
        assertThat(map.get("nanoKey")).isEqualTo("nanoVal");

        timeSource.advance(Duration.ofNanos(10)); // exactly expires
        assertThat(map.get("nanoKey")).isNull();
    }

    @Test
    @DisplayName("Boundary: Rapid sequential overrides on same key always reflect latest")
    void testRapidSequentialOverrides() {
        ExpiringMap<String, Integer> map = ExpiringMap.<String, Integer>builder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .build();

        for (int i = 1; i <= 200; i++) {
            map.put("counter", i);
            timeSource.advance(Duration.ofMillis(50));
            assertThat(map.get("counter")).isEqualTo(i);
        }

        // Total time elapsed: 200 * 50ms = 10s.
        // If expiry hadn't refreshed on every put, the initial entry would have expired!
        // But because it was updated 50ms ago, it has almost full 10s remaining!
        assertThat(map.get("counter")).isEqualTo(200);

        Optional<Duration> remaining = map.getRemainingTtl("counter");
        assertThat(remaining).isPresent();
        assertThat(remaining.get().toMillis()).isGreaterThan(9000);
    }

    @Test
    @DisplayName("Boundary: Repeated expiration and revival cycles")
    void testMultipleExpirationRevivalCycles() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(2))
                .timeSource(timeSource)
                .build();

        for (int cycle = 1; cycle <= 10; cycle++) {
            String val = "v" + cycle;
            map.put("cycleKey", val);
            assertThat(map.get("cycleKey")).isEqualTo(val);

            // Let it expire
            timeSource.advance(Duration.ofSeconds(3));
            assertThat(map.get("cycleKey")).isNull();
            assertThat(map.containsKey("cycleKey")).isFalse();
            assertThat(map.size()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Boundary: putUntil validations and expiry")
    void testPutUntilBoundaries() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofMinutes(5))
                .timeSource(timeSource)
                .build();

        Instant now = timeSource.now();

        // Expire in the past should throw
        assertThatThrownBy(() -> map.putUntil("k", "v", now.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        // Expire right now should throw
        assertThatThrownBy(() -> map.putUntil("k", "v", now))
                .isInstanceOf(IllegalArgumentException.class);

        // Expire in 5 seconds
        map.putUntil("k", "v", now.plusSeconds(5));
        assertThat(map.get("k")).isEqualTo("v");

        timeSource.advance(Duration.ofSeconds(5));
        assertThat(map.get("k")).isNull();
    }

    @Test
    @DisplayName("Boundary: resetExpiration on absent or expired keys")
    void testResetExpirationBoundaries() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        // Absent key
        assertThat(map.resetExpiration("absent")).isFalse();

        // Present key
        map.put("present", "val");
        assertThat(map.resetExpiration("present")).isTrue();

        // Present key expired
        timeSource.advance(Duration.ofSeconds(6));
        assertThat(map.resetExpiration("present")).isFalse();
    }

    @Test
    @DisplayName("Boundary: CleanUp under diverse conditions")
    void testCleanUpDiverseConditions() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        // Empty cleanUp
        map.cleanUp();
        assertThat(map.isEmpty()).isTrue();

        // All active
        map.put("k1", "v1");
        map.put("k2", "v2");
        map.cleanUp();
        assertThat(map.size()).isEqualTo(2);

        // Mix: k1 expires at 5s, k2 expires at 20s
        map.put("k2", "v2", Duration.ofSeconds(20));
        timeSource.advance(Duration.ofSeconds(6));
        map.cleanUp();
        assertThat(map.size()).isEqualTo(1);
        assertThat(map.containsKey("k1")).isFalse();
        assertThat(map.containsKey("k2")).isTrue();

        // All expired
        timeSource.advance(Duration.ofSeconds(15));
        map.cleanUp();
        assertThat(map.isEmpty()).isTrue();
    }
}
