package com.expiringmap;

import java.util.Arrays;
import java.util.Objects;

/**
 * A wrapper for array keys (such as {@code byte[]}, {@code int[]}, {@code Object[]})
 * providing value-based {@link #equals(Object)} and {@link #hashCode()} implementations.
 *
 * <p>In standard Java, arrays use identity reference equality. Wrapping an array with
 * {@code ArrayKey} allows using arrays (e.g., cryptographic hashes, binary tokens)
 * as map keys reliably.
 */
public final class ArrayKey {

    private final Object array;
    private final int hashCode;

    private ArrayKey(Object array, int hashCode) {
        this.array = array;
        this.hashCode = hashCode;
    }

    public static ArrayKey of(byte[] array) {
        Objects.requireNonNull(array, "array cannot be null");
        return new ArrayKey(array, Arrays.hashCode(array));
    }

    public static ArrayKey of(char[] array) {
        Objects.requireNonNull(array, "array cannot be null");
        return new ArrayKey(array, Arrays.hashCode(array));
    }

    public static ArrayKey of(int[] array) {
        Objects.requireNonNull(array, "array cannot be null");
        return new ArrayKey(array, Arrays.hashCode(array));
    }

    public static ArrayKey of(long[] array) {
        Objects.requireNonNull(array, "array cannot be null");
        return new ArrayKey(array, Arrays.hashCode(array));
    }

    public static ArrayKey of(Object[] array) {
        Objects.requireNonNull(array, "array cannot be null");
        return new ArrayKey(array, Arrays.deepHashCode(array));
    }

    @SuppressWarnings("unchecked")
    public <T> T getArray() {
        return (T) array;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArrayKey)) return false;
        ArrayKey other = (ArrayKey) o;
        if (this.hashCode != other.hashCode) return false;

        if (array instanceof byte[] && other.array instanceof byte[]) {
            return Arrays.equals((byte[]) array, (byte[]) other.array);
        }
        if (array instanceof char[] && other.array instanceof char[]) {
            return Arrays.equals((char[]) array, (char[]) other.array);
        }
        if (array instanceof int[] && other.array instanceof int[]) {
            return Arrays.equals((int[]) array, (int[]) other.array);
        }
        if (array instanceof long[] && other.array instanceof long[]) {
            return Arrays.equals((long[]) array, (long[]) other.array);
        }
        if (array instanceof Object[] && other.array instanceof Object[]) {
            return Arrays.deepEquals((Object[]) array, (Object[]) other.array);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        if (array instanceof byte[]) return Arrays.toString((byte[]) array);
        if (array instanceof char[]) return Arrays.toString((char[]) array);
        if (array instanceof int[]) return Arrays.toString((int[]) array);
        if (array instanceof long[]) return Arrays.toString((long[]) array);
        if (array instanceof Object[]) return Arrays.deepToString((Object[]) array);
        return String.valueOf(array);
    }
}
