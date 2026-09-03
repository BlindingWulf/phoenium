package dev.phoenium.task;

/** Closed test payload vocabulary keeps cross-owner work reviewable. */
public sealed interface TaskPayload permits TaskPayload.Append, TaskPayload.Add, TaskPayload.Fail {
    record Append(String value) implements TaskPayload { }
    record Add(long value) implements TaskPayload { }
    record Fail(String message) implements TaskPayload { }
}
