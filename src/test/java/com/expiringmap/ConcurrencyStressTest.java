package com.expiringmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyStressTest {

    @Test
    @DisplayName("Stress: Concurrent race between thread overriding key and thread reading/expiring it")
    void testConcurrentOverrideVsExpirationRace() throws Exception {
        int writerThreads = 8;
        int readerThreads = 8;
        int iterations = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(writerThreads + readerThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(writerThreads + readerThreads);

        // Map with short TTL to simulate rapid expiration cycles
        ExpiringMap<String, Long> map = ExpiringMap.<String, Long>builder()
                .defaultTtl(Duration.ofMillis(5))
                .build();

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger nullReads = new AtomicInteger(0);
        AtomicInteger validReads = new AtomicInteger(0);
        AtomicLong maxSequenceWritten = new AtomicLong(0);

        // Writers constantly increment and override "raceKey"
        for (int i = 0; i < writerThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterations; j++) {
                        long seq = maxSequenceWritten.incrementAndGet();
                        map.put("raceKey", seq);
                        // Occasionally sleep 1ms to allow expiration windows
                        if (j % 50 == 0) {
                            Thread.sleep(1);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Readers constantly read "raceKey"
        for (int i = 0; i < readerThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long lastObserved = -1;
                    for (int j = 0; j < iterations; j++) {
                        Long val = map.get("raceKey");
                        if (val != null) {
                            validReads.incrementAndGet();
                            // Value must be monotonically reasonable (cannot be negative or greater than max written)
                            assertThat(val).isGreaterThan(0L);
                            assertThat(val).isLessThanOrEqualTo(maxSequenceWritten.get());
                            lastObserved = val;
                        } else {
                            nullReads.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(validReads.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Stress: Concurrent multi-key churn with random operations")
    void testConcurrentMultiKeyRandomOperations() throws Exception {
        int threads = 12;
        int operationsPerThread = 1500;
        int numKeys = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExpiringMap<Integer, String> map = ExpiringMap.<Integer, String>builder()
                .defaultTtl(Duration.ofMillis(20))
                .build();

        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int op = 0; op < operationsPerThread; op++) {
                        int key = random.nextInt(numKeys);
                        int action = random.nextInt(5);

                        switch (action) {
                            case 0: // put with default TTL
                                map.put(key, "T" + threadId + "-V" + op);
                                break;
                            case 1: // put with custom TTL
                                map.put(key, "T" + threadId + "-Custom-" + op, Duration.ofMillis(random.nextInt(10, 50)));
                                break;
                            case 2: // get
                                String val = map.get(key);
                                if (val != null) {
                                    assertThat(val).startsWith("T");
                                }
                                break;
                            case 3: // putIfAbsent
                                map.putIfAbsent(key, "T" + threadId + "-Absent-" + op);
                                break;
                            case 4: // remove
                                map.remove(key);
                                break;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        for (Future<?> f : futures) {
            f.get(); // ensure no unexpected exceptions
        }
    }

    @Test
    @DisplayName("Stress: Concurrent Heterogeneous Map access across different types")
    void testConcurrentHeterogeneousMap() throws Exception {
        int threads = 8;
        int opsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        HeterogeneousExpiringMap map = ExpiringMap.heterogeneousBuilder()
                .defaultTtl(Duration.ofMillis(50))
                .buildHeterogeneous();

        TypedKey<Integer> COUNTER = TypedKey.of("metrics.counter", Integer.class);
        TypedKey<String> STATUS = TypedKey.of("system.status", String.class);
        TypedKey<Double> LATENCY = TypedKey.of("metrics.latency", Double.class);

        map.put(COUNTER, 0);
        map.put(STATUS, "INITIALIZING");
        map.put(LATENCY, 1.25);

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        // Concurrent override
                        map.put(COUNTER, threadId * 10000 + j);
                        map.put(STATUS, "RUNNING-" + threadId);
                        map.put(LATENCY, 2.5 + threadId);

                        // Concurrent read
                        Integer c = map.get(COUNTER);
                        String s = map.get(STATUS);
                        Double l = map.get(LATENCY);

                        if (c != null) assertThat(c).isGreaterThanOrEqualTo(0);
                        if (s != null) assertThat(s).startsWith("RUNNING-");
                        if (l != null) assertThat(l).isGreaterThanOrEqualTo(2.5);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
    }
}
