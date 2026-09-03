package dev.phoenium.task;
public record TaskCorrelationId(long value) { public TaskCorrelationId { if (value < 0) throw new IllegalArgumentException("negative correlation id"); } }
