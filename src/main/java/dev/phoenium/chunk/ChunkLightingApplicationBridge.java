package dev.phoenium.chunk;

/** Future lighting adapter boundary: compute stays coordinated; live flags/notifications re-enter an owner. */
public interface ChunkLightingApplicationBridge {
    void submitCompute(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work, String batchKey);
    void applyLiveNotification(PendingVanillaStage<?, ?> stage);
}
