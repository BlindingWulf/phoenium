package dev.phoenium.task;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.task.TaskCorrelationId;

/** Closed test payload vocabulary keeps cross-owner work reviewable. */
public sealed interface TaskPayload permits TaskPayload.Append, TaskPayload.Add, TaskPayload.Fail, TaskPayload.LiveLevelTick,
    TaskPayload.OwnedObjectOperation, TaskPayload.ChunkCompletion {
    record Append(String value) implements TaskPayload { }
    record Add(long value) implements TaskPayload { }
    record Fail(String message) implements TaskPayload { }
    /** A descriptor only: live Minecraft objects never travel in an owner envelope. */
    record LiveLevelTick(String worldKey) implements TaskPayload { }
    /** Object identity and epoch only; a live entity/player/packet is retained at its integration boundary. */
    record OwnedObjectOperation(String objectId, OwnerEpoch expectedObjectEpoch, String operationKind,
                                TaskCorrelationId correlationId) implements TaskPayload { }
    /** Immutable producer result metadata; producer-side data has no live-world mutation authority. */
    record ChunkCompletion(String chunkKey, long requestGeneration, String completionKind,
                           TaskCorrelationId correlationId) implements TaskPayload { }
}
