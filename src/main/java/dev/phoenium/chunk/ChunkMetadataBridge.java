package dev.phoenium.chunk;

/** Future map/type metadata boundary: a coordinated global service with per-key/lifecycle ordering. */
public interface ChunkMetadataBridge {
    /** Ordered observation only; it does not authorize a later backing-cache mutation. */
    ChunkTypeMetadataService.Update observe(ChunkCustodyModel.WorkId work, dev.phoenium.topology.RoutingKey target);
}
