package dev.phoenium.core;
public record TopologyGeneration(long value) { public TopologyGeneration { if (value < 0) throw new IllegalArgumentException("negative topology generation"); } public TopologyGeneration next() { return new TopologyGeneration(value + 1); } }
