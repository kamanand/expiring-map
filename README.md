# Expiring Map

A high-performance, thread-safe Java key-value map with configurable expiration, background cleanup, and expiration listeners. Each `put` replaces the current value for its key, and the replacement's TTL starts when it is inserted.

## Requirements

- Java 25 (LTS) or newer
- Maven

## Usage

```java
import com.expiringmap.ExpiringMap;
import java.time.Duration;

// 1. Create a map with default TTL
ExpiringMap<String, String> cache = ExpiringMap.<String, String>builder()
        .defaultTtl(Duration.ofMinutes(5))
        .cleanUpInterval(Duration.ofSeconds(1)) // Optional background cleaner sweep
        .expirationListener((key, value) -> System.out.println("Expired: " + key))
        .build();

// 2. Insert with default TTL
cache.put("user:42", "Ada");

// 3. Insert with custom per-entry TTL
cache.put("session:42", "active", Duration.ofSeconds(30));

// 4. Overwriting a key resets its TTL
cache.put("user:42", "Ada Lovelace");

// 5. Querying values
String user = cache.get("user:42");
```

Expired entries are treated as absent by `get`, `containsKey`, and `remove`.
`size` also removes expired entries while counting. When configured with a cleanup interval, an active daemon background worker periodically removes expired entries, ensuring expired values do not stay retained if their keys are never accessed.

Keys and values must be non-null, and all TTLs must be greater than zero.

## Build and Test

```sh
# Run all tests using the runner script
./run.sh test

# Run the live interactive feature demonstration
./run.sh demo
```
