package dev.phoenium.task;
public record TaskSequence(long value) implements Comparable<TaskSequence> { public TaskSequence { if (value < 0) throw new IllegalArgumentException("negative sequence"); } @Override public int compareTo(TaskSequence other) { return Long.compare(value, other.value); } }
