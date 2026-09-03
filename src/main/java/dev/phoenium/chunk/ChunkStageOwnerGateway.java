package dev.phoenium.chunk;

import dev.phoenium.topology.RegionRoute;

/** Owner-inbox integration point for typed pending chunk stages; it admits no arbitrary closures. */
@FunctionalInterface
public interface ChunkStageOwnerGateway {
    Delivery submit(RegionRoute currentRoute, PendingVanillaStage<?, ?> stage);
    enum Delivery { QUEUED, REJECTED_SHUTDOWN, REJECTED_ROUTE }
}
