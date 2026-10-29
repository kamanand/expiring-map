package com.expiringmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundCleanerLifecycleTest {

    @Test
    @DisplayName("Lifecycle: Listener exceptions do not crash background cleaner")
    void testListenerExceptionsDoNotCrashCleaner() throws Exception {
        AtomicInteger listenerInvocations = new AtomicInteger(0);

        try (ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofMillis(50))
                .cleanUpInterval(Duration.ofMillis(30))
                .expirationListener((k, v) -> {
                    listenerInvocations.incrementAndGet();
                    throw new RuntimeException("Deliberate error in user listener callback!");
                })
                .build()) {

            map.put("key1", "val1");
            Thread.sleep(120);

            // Listener was called despite throwing exception
            assertThat(listenerInvocations.get()).isGreaterThanOrEqualTo(1);

            // The map must continue to function normally
            map.put("key2", "val2");
            assertThat(map.get("key2")).isEqualTo("val2");

            Thread.sleep(120);
            assertThat(listenerInvocations.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("Lifecycle: Map closes background threads cleanly")
    void testCleanShutdownOnClose() {
        ExpiringMap<String, String> map = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofMinutes(1))
                .cleanUpInterval(Duration.ofMillis(100))
                .build();

        map.put("k", "v");
        map.close();

        // Closing multiple times is safe and idempotent
        map.close();
    }
}
