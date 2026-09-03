package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;

/** Immutable identity issued before an async producer starts. It is never a mutable chunk or future. */
public record ChunkWorkRequest(WorldId world, RoutingKey chunkKey, long requestGeneration,
                               TopologyGeneration requestedTopologyGeneration, String completionKind,
                               TaskCorrelationId correlationId) {
    public ChunkWorkRequest {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(chunkKey, "chunkKey");
        Objects.requireNonNull(requestedTopologyGeneration, "requestedTopologyGeneration"); Objects.requireNonNull(completionKind, "completionKind"); Objects.requireNonNull(correlationId, "correlationId");
        if (!world.equals(chunkKey.world()) || requestGeneration < 0) throw new IllegalArgumentException("invalid chunk work identity");
    }
}
