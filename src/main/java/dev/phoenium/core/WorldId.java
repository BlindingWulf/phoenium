package dev.phoenium.core;

import java.util.Objects;

public record WorldId(String value) {
    public WorldId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("world identity is blank");
    }
    // S9-D13: the record's equality and hash (value's), written out so hot routing checks do not dispatch through
    // the record's method handles; the same instance answers first.
    @Override public boolean equals(Object other) { return this == other || other instanceof WorldId world && value.equals(world.value); }
    @Override public int hashCode() { return value.hashCode(); }

    private static final java.util.concurrent.ConcurrentHashMap<String, WorldId> CANONICAL = new java.util.concurrent.ConcurrentHashMap<>();
    /** S9-D17: the one instance live code names {@code value}'s world by, so world tables, keys and routes built from
     * it compare by identity first. Equality is unchanged: a separately constructed instance is still equal. */
    public static WorldId of(String value) {
        WorldId found = CANONICAL.get(Objects.requireNonNull(value, "value"));
        return found != null ? found : CANONICAL.computeIfAbsent(value, WorldId::new);
    }
}
