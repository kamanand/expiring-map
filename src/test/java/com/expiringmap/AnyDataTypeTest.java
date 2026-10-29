package com.expiringmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AnyDataTypeTest {

    private TestTimeSource timeSource;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
    }

    // --- Domain Types for Testing ---

    enum UserRole { ADMIN, OPERATOR, VIEWER }
    enum AccountStatus { ACTIVE, SUSPENDED, DELETED }

    static final class UserRecord {
        private final UUID id;
        private final String name;
        private final UserRole role;

        UserRecord(UUID id, String name, UserRole role) {
            this.id = id;
            this.name = name;
            this.role = role;
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
        public UserRole getRole() { return role; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UserRecord)) return false;
            UserRecord that = (UserRecord) o;
            return Objects.equals(id, that.id) &&
                    Objects.equals(name, that.name) &&
                    role == that.role;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, role);
        }
    }

    static final class CompoundKey {
        private final String tenantId;
        private final long sequence;

        CompoundKey(String tenantId, long sequence) {
            this.tenantId = tenantId;
            this.sequence = sequence;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CompoundKey)) return false;
            CompoundKey that = (CompoundKey) o;
            return sequence == that.sequence && Objects.equals(tenantId, that.tenantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, sequence);
        }
    }

    @Test
    @DisplayName("Data Type 1: Custom POJOs & Records as Values and UUID as Key")
    void testCustomDomainObjects() {
        ExpiringMap<UUID, UserRecord> userMap = ExpiringMap.<UUID, UserRecord>builder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .build();

        UUID aliceId = UUID.randomUUID();
        UserRecord aliceV1 = new UserRecord(aliceId, "Alice", UserRole.VIEWER);
        UserRecord aliceV2 = new UserRecord(aliceId, "Alice", UserRole.ADMIN);

        // Put initial
        userMap.put(aliceId, aliceV1);
        assertThat(userMap.get(aliceId)).isEqualTo(aliceV1);
        assertThat(userMap.get(aliceId).getRole()).isEqualTo(UserRole.VIEWER);

        // Override existing key -> picks latest value
        userMap.put(aliceId, aliceV2);
        assertThat(userMap.get(aliceId)).isEqualTo(aliceV2);
        assertThat(userMap.get(aliceId).getRole()).isEqualTo(UserRole.ADMIN);

        // Expiration check
        timeSource.advance(Duration.ofSeconds(11));
        assertThat(userMap.get(aliceId)).isNull();
    }

    @Test
    @DisplayName("Data Type 2: Boxed Primitives (Long, Double, Boolean, Integer)")
    void testBoxedPrimitives() {
        ExpiringMap<Long, Double> metrics = ExpiringMap.<Long, Double>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        metrics.put(1001L, 98.6);
        metrics.put(1002L, 102.4, Duration.ofSeconds(15)); // Custom TTL

        assertThat(metrics.get(1001L)).isEqualTo(98.6);
        assertThat(metrics.get(1002L)).isEqualTo(102.4);

        // Override value
        metrics.put(1001L, 99.1);
        assertThat(metrics.get(1001L)).isEqualTo(99.1); // Picks latest

        timeSource.advance(Duration.ofSeconds(6));
        assertThat(metrics.get(1001L)).isNull(); // Expired after 5s
        assertThat(metrics.get(1002L)).isEqualTo(102.4); // Still valid (15s TTL)
    }

    @Test
    @DisplayName("Data Type 3: Enums as both Keys and Values")
    void testEnumsAsKeysAndValues() {
        ExpiringMap<UserRole, AccountStatus> roleStatusMap = ExpiringMap.<UserRole, AccountStatus>builder()
                .defaultTtl(Duration.ofMinutes(1))
                .timeSource(timeSource)
                .build();

        roleStatusMap.put(UserRole.ADMIN, AccountStatus.ACTIVE);
        roleStatusMap.put(UserRole.VIEWER, AccountStatus.SUSPENDED);

        assertThat(roleStatusMap.get(UserRole.ADMIN)).isEqualTo(AccountStatus.ACTIVE);

        // Override
        roleStatusMap.put(UserRole.ADMIN, AccountStatus.DELETED);
        assertThat(roleStatusMap.get(UserRole.ADMIN)).isEqualTo(AccountStatus.DELETED);
    }

    @Test
    @DisplayName("Data Type 4: Complex Collections as Values (List, Map, Set)")
    void testCollectionsAsValues() {
        ExpiringMap<String, List<String>> tagsMap = ExpiringMap.<String, List<String>>builder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .build();

        List<String> tagsV1 = List.of("java", "concurrency");
        List<String> tagsV2 = List.of("java", "concurrency", "generics", "cache");

        tagsMap.put("topic:1", tagsV1);
        assertThat(tagsMap.get("topic:1")).containsExactly("java", "concurrency");

        // Override with newer list
        tagsMap.put("topic:1", tagsV2);
        assertThat(tagsMap.get("topic:1")).containsExactly("java", "concurrency", "generics", "cache");
    }

    @Test
    @DisplayName("Data Type 5: Compound / Composite Keys")
    void testCompoundKeys() {
        ExpiringMap<CompoundKey, String> compoundMap = ExpiringMap.<CompoundKey, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        CompoundKey k1 = new CompoundKey("tenant-A", 42L);
        CompoundKey k1Lookup = new CompoundKey("tenant-A", 42L);

        compoundMap.put(k1, "payload-v1");
        assertThat(compoundMap.get(k1Lookup)).isEqualTo("payload-v1");

        // Override with latest
        compoundMap.put(k1Lookup, "payload-v2");
        assertThat(compoundMap.get(k1)).isEqualTo("payload-v2");
    }

    @Test
    @DisplayName("Data Type 6: Array Keys using ArrayKey (byte[], int[])")
    void testArrayKeys() {
        ExpiringMap<ArrayKey, String> binaryMap = ExpiringMap.<ArrayKey, String>builder()
                .defaultTtl(Duration.ofSeconds(5))
                .timeSource(timeSource)
                .build();

        byte[] tokenBytes1 = new byte[]{0x0A, 0x1F, 0x2C};
        byte[] tokenBytesLookup = new byte[]{0x0A, 0x1F, 0x2C}; // Different array instance, same content

        binaryMap.put(ArrayKey.of(tokenBytes1), "session-secret-v1");
        assertThat(binaryMap.get(ArrayKey.of(tokenBytesLookup))).isEqualTo("session-secret-v1");

        // Override
        binaryMap.put(ArrayKey.of(tokenBytesLookup), "session-secret-v2");
        assertThat(binaryMap.get(ArrayKey.of(tokenBytes1))).isEqualTo("session-secret-v2");
    }

    @Test
    @DisplayName("Data Type 7: Heterogeneous Storage - Multiple distinct data types in the SAME map")
    void testHeterogeneousExpiringMap() {
        HeterogeneousExpiringMap map = ExpiringMap.heterogeneousBuilder()
                .defaultTtl(Duration.ofSeconds(10))
                .timeSource(timeSource)
                .buildHeterogeneous();

        // Define typed keys for completely different data types
        TypedKey<String> USERNAME = TypedKey.of("user.name", String.class);
        TypedKey<Integer> USER_AGE = TypedKey.of("user.age", Integer.class);
        TypedKey<Double> BALANCE = TypedKey.of("account.balance", Double.class);
        TypedKey<UserRecord> USER_PROFILE = TypedKey.of("user.profile", UserRecord.class);
        TypedKey<Boolean> IS_ACTIVE = TypedKey.of("user.active", Boolean.class);

        // Store different types
        map.put(USERNAME, "Alice");
        map.put(USER_AGE, 30);
        map.put(BALANCE, 1542.50, Duration.ofSeconds(20)); // Custom TTL
        map.put(USER_PROFILE, new UserRecord(UUID.randomUUID(), "Alice Smith", UserRole.ADMIN));
        map.put(IS_ACTIVE, true);

        // Retrieve with compile-time type safety (no casts!)
        String name = map.get(USERNAME);
        Integer age = map.get(USER_AGE);
        Double balance = map.get(BALANCE);
        UserRecord profile = map.get(USER_PROFILE);
        Boolean active = map.get(IS_ACTIVE);

        assertThat(name).isEqualTo("Alice");
        assertThat(age).isEqualTo(30);
        assertThat(balance).isEqualTo(1542.50);
        assertThat(profile.getName()).isEqualTo("Alice Smith");
        assertThat(active).isTrue();

        // Override existing value with latest value
        map.put(USER_AGE, 31);
        assertThat(map.get(USER_AGE)).isEqualTo(31); // Always latest value

        // Expiry check
        timeSource.advance(Duration.ofSeconds(11));
        assertThat(map.get(USERNAME)).isNull(); // 10s default TTL expired
        assertThat(map.get(USER_AGE)).isNull();
        assertThat(map.get(BALANCE)).isEqualTo(1542.50); // 20s TTL still active!
    }
}
