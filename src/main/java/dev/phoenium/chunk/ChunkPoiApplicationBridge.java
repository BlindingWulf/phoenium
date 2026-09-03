package dev.phoenium.chunk;

/** Future POI adapter boundary: detached prefetch/indexing is distinct from owner installation. */
public interface ChunkPoiApplicationBridge {
    void prefetchOrIndex(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work, String sectionKey);
    void installOrReconcile(PendingVanillaStage<?, ?> stage);
}
