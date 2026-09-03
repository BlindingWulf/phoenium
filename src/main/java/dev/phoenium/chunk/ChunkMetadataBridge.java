package dev.phoenium.chunk;

/** Future map/type metadata boundary: a coordinated global service with per-key/lifecycle ordering. */
public interface ChunkMetadataBridge {
    void orderStorageMutation(ChunkCustodyModel.LifecycleId lifecycle, String metadataKey);
    void validateLifecycle(ChunkCustodyModel.LifecycleId lifecycle);
}
