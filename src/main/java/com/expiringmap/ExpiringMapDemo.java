package com.expiringmap;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Live demonstration showcasing all features of the ExpiringMap library.
 */
public final class ExpiringMapDemo {

    public static void main(String[] args) throws Exception {
        log("==================================================================");
        log("          EXPIRING MAP - LIVE EXECUTION & FEATURE DEMO            ");
        log("==================================================================");

        // 1. Fluent Builder with Default TTL and Expiration Listener
        log("\n--- Scenario 1: Builder Configuration & Expiration Listener ---");
        try (ExpiringMap<String, String> cache = ExpiringMap.<String, String>builder()
                .defaultTtl(Duration.ofMillis(800))
                .cleanUpInterval(Duration.ofMillis(500))
                .expirationListener((key, value) ->
                        log("  >>> [Listener Event] Entry expired: key='" + key + "', value='" + value + "'"))
                .build()) {

            log("Created ExpiringMap with default TTL = 800ms and active cleaner sweep every 500ms");

            cache.put("user:101", "Ada Lovelace");
            log("Inserted 'user:101' -> 'Ada Lovelace'");
            log("Immediate get('user:101'): " + cache.get("user:101"));

            Optional<Duration> remaining = cache.getRemainingTtl("user:101");
            log("Remaining TTL: " + remaining.map(Duration::toMillis).orElse(-1L) + " ms");

            log("Sleeping 400ms (within TTL)...");
            Thread.sleep(400);
            log("After 400ms - get('user:101'): " + cache.get("user:101") + " (active)");

            log("Sleeping another 500ms (total 900ms > 800ms TTL)...");
            Thread.sleep(500);
            log("After 900ms - get('user:101'): " + cache.get("user:101") + " (expired & evicted)");
            log("containsKey('user:101'): " + cache.containsKey("user:101"));

            // 2. Per-Entry Custom TTL
            log("\n--- Scenario 2: Per-Entry Custom TTL ---");
            cache.put("session:short", "token-FAST", Duration.ofMillis(300));
            cache.put("session:long",  "token-PERSISTENT", Duration.ofMillis(1200));
            log("Inserted 'session:short' with 300ms custom TTL");
            log("Inserted 'session:long' with 1200ms custom TTL");
            log("Current active size: " + cache.size());

            log("Sleeping 450ms...");
            Thread.sleep(450);
            log("After 450ms:");
            log("  'session:short': " + cache.get("session:short") + " (expired after 300ms)");
            log("  'session:long':  " + cache.get("session:long") + " (still active, 1200ms TTL)");
            log("Current active size: " + cache.size());

            // 3. Resetting Expiration on Access or Update
            log("\n--- Scenario 3: Expiration Reset & Sliding Lifetime ---");
            cache.put("cache:item", "original-value", Duration.ofMillis(500));
            log("Inserted 'cache:item' with 500ms TTL");

            log("Sleeping 300ms...");
            Thread.sleep(300);
            boolean reset = cache.resetExpiration("cache:item", Duration.ofMillis(600));
            log("Reset TTL to 600ms? " + reset);

            log("Sleeping 400ms (700ms total, would have expired without reset)...");
            Thread.sleep(400);
            log("get('cache:item'): " + cache.get("cache:item") + " (still alive!)");

            // 4. Detailed Entry Metadata (getEntry)
            log("\n--- Scenario 4: Entry Metadata Inspection ---");
            cache.getEntry("cache:item").ifPresent(entry -> {
                log("Entry Metadata for 'cache:item':");
                log("  Value: " + entry.getValue());
                log("  Created At: " + entry.getCreatedAt());
                log("  Expires At: " + entry.getExpiresAt());
                log("  Remaining lifetime: " + entry.getRemainingTtl().toMillis() + " ms");
            });

            // 5. Automatic Background Sweeping
            log("\n--- Scenario 5: Background Cleaner Worker ---");
            cache.put("unaccessed:1", "data1", Duration.ofMillis(200));
            cache.put("unaccessed:2", "data2", Duration.ofMillis(200));
            log("Inserted 2 unaccessed entries with 200ms TTL");
            log("Sleeping 1.2s to let cleaner worker sweep automatically...");
            Thread.sleep(1200);
            log("Map size after sleep: " + cache.size() + " (purged in background)");
        }

        // 6. Heterogeneous Type-Safe Expiring Map
        log("\n--- Scenario 6: Type-Safe Heterogeneous Expiring Map ---");
        TypedKey<String> USER_NAME = TypedKey.of("username", String.class);
        TypedKey<Integer> USER_AGE = TypedKey.of("user_age", Integer.class);
        TypedKey<Boolean> IS_ADMIN = TypedKey.of("is_admin", Boolean.class);

        try (HeterogeneousExpiringMap hetMap = HeterogeneousExpiringMap.create(Duration.ofMillis(800))) {

            hetMap.put(USER_NAME, "Alice");
            hetMap.put(USER_AGE, 30);
            hetMap.put(IS_ADMIN, true, Duration.ofMillis(300));

            log("Inserted heterogeneous types safely:");
            log("  USER_NAME (String):  " + hetMap.get(USER_NAME));
            log("  USER_AGE (Integer):  " + hetMap.get(USER_AGE));
            log("  IS_ADMIN (Boolean):  " + hetMap.get(IS_ADMIN));

            log("Sleeping 400ms (IS_ADMIN will expire, others will remain)...");
            Thread.sleep(400);
            log("After 400ms:");
            log("  USER_NAME: " + hetMap.get(USER_NAME));
            log("  USER_AGE:  " + hetMap.get(USER_AGE));
            log("  IS_ADMIN:  " + hetMap.get(IS_ADMIN) + " (expired)");
        }

        log("\n==================================================================");
        log("          DEMONSTRATION COMPLETED SUCCESSFULLY                   ");
        log("==================================================================");
    }

    private static void log(String message) {
        System.out.printf("[%s] %s%n", Instant.now().toString(), message);
    }
}
