package com.expiringmap;

import java.util.Objects;

/**
 * A type-safe key token used to store and retrieve heterogeneous data types
 * in an {@link ExpiringMap} or {@link HeterogeneousExpiringMap}.
 *
 * <p>Enables a single map instance to safely store different types of values
 * (e.g. {@code String}, {@code Integer}, {@code List<Order>}, custom domain POJOs)
 * with compile-time type safety and zero manual casting.
 *
 * @param <T> the type of value associated with this key
 */
public final class TypedKey<T> {

    private final String name;
    private final Class<T> type;

    private TypedKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
    }

    /**
     * Creates a new typed key with the given name and expected value class.
     *
     * @param name unique identifier name for the key
     * @param type class of the value associated with this key
     * @param <T> value type
     * @return a new TypedKey
     */
    public static <T> TypedKey<T> of(String name, Class<T> type) {
        return new TypedKey<>(name, type);
    }

    public String getName() {
        return name;
    }

    public Class<T> getType() {
        return type;
    }

    /**
     * Safely casts an arbitrary object to the type expected by this key.
     *
     * @param value object to cast
     * @return value cast to type {@code T}
     * @throws ClassCastException if the object is not an instance of {@link #getType()}
     */
    public T cast(Object value) {
        return value == null ? null : type.cast(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TypedKey)) return false;
        TypedKey<?> typedKey = (TypedKey<?>) o;
        return Objects.equals(name, typedKey.name) && Objects.equals(type, typedKey.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return "TypedKey{" + name + "<" + type.getSimpleName() + ">}";
    }
}
