package dev.phoenium.service;

import dev.phoenium.region.RegionRuntime;
import dev.phoenium.task.TaskPayload;

/** Delivery endpoint for closed, retained regional-service operation identities. */
@FunctionalInterface
public interface RegionalServiceExecutor {
    void execute(RegionRuntime runtime, TaskPayload.RegionalService operation) throws Exception;
    default boolean locallyAdmitted(TaskPayload.RegionalService operation) { return false; }
    default long enqueuedNanos(TaskPayload.RegionalService operation) { throw new IllegalStateException("no retained local enqueue receipt"); }
    default boolean prepareLocal(RegionRuntime runtime, TaskPayload.RegionalService operation) throws Exception { return true; }
    default void declineLocal(TaskPayload.RegionalService operation) { throw new IllegalStateException("local selection cannot be declined"); }
    default boolean localDeclined(TaskPayload.RegionalService operation) { return false; }
}
