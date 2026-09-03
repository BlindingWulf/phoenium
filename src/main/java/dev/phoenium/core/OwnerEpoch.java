package dev.phoenium.core;
public record OwnerEpoch(long value) { public OwnerEpoch { if (value < 0) throw new IllegalArgumentException("negative owner epoch"); } public OwnerEpoch next() { return new OwnerEpoch(value + 1); } }
