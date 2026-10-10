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
}
