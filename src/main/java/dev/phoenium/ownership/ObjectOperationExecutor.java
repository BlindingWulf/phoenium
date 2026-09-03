package dev.phoenium.ownership;

import dev.phoenium.region.RegionRuntime;
import dev.phoenium.task.TaskPayload;

/** Closed Stage 3 execution seam; it never accepts an arbitrary Runnable or a producer-thread callback. */
public interface ObjectOperationExecutor {
    void execute(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) throws Exception;
    void execute(RegionRuntime runtime, TaskPayload.ChunkCompletion completion) throws Exception;
}
