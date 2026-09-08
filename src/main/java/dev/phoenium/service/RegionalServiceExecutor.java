package dev.phoenium.service;

import dev.phoenium.region.RegionRuntime;
import dev.phoenium.task.TaskPayload;

/** Delivery endpoint for closed, retained regional-service operation identities. */
@FunctionalInterface
public interface RegionalServiceExecutor {
    void execute(RegionRuntime runtime, TaskPayload.RegionalService operation) throws Exception;
    default boolean locallyAdmitted(TaskPayload.RegionalService operation) { return false; }
}
