package dev.phoenium.ownership;
public record LeaseEpoch(long value) { public LeaseEpoch { if (value < 0) throw new IllegalArgumentException("negative lease epoch"); } }
