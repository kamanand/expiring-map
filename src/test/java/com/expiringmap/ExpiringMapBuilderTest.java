package com.expiringmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExpiringMapBuilderTest {

    @Test
    @DisplayName("Builder: Validates default TTL and configurations")
    void testBuilderDefaultsAndValidation() {
        ExpiringMap<String, String> defaultMap = ExpiringMap.<String, String>builder().build();
        assertThat(defaultMap.getDefaultTtl()).isEqualTo(Duration.ofMinutes(10));

        ExpiringMap<String, String> customMap = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofSeconds(45))
                .build();
        assertThat(customMap.getDefaultTtl()).isEqualTo(Duration.ofSeconds(45));

        assertThatThrownBy(() -> ExpiringMap.builder().defaultTtl(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExpiringMap.builder().defaultTtl(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpiringMap.builder().defaultTtl(-5, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpiringMap.builder().cleanUpInterval(0, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpiringMap.builder().timeSource(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Active Background Cleanup: Sweeps expired entries automatically")
    void testActiveBackgroundCleaner() throws Exception {
        List<String> expiredKeys = new CopyOnWriteArrayList<>();

        // Real-time test with short TTL and fast cleanup interval
        try (ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofMillis(100))
                .cleanUpInterval(Duration.ofMillis(50))
                .threadNamePrefix("TestCleaner")
                .expirationListener((k, v) -> expiredKeys.add(k))
                .build()) {

            map.put("activeKey1", "v1");
            map.put("activeKey2", "v2");

            // Wait for background cleanup thread to trigger
            long startTime = System.currentTimeMillis();
            while (expiredKeys.size() < 2 && (System.currentTimeMillis() - startTime) < 2000) {
                Thread.sleep(25);
            }

            assertThat(expiredKeys).containsExactlyInAnyOrder("activeKey1", "activeKey2");
            assertThat(map.isEmpty()).isTrue();
        }
    }
}
