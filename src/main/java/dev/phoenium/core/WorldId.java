package dev.phoenium.core;

import java.util.Objects;

public record WorldId(String value) {
    public WorldId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("world identity is blank");
    }
}
