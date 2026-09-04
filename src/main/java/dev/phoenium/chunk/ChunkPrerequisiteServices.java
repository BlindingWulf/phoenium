package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import java.util.Objects;

/** Real per-world lifetime root for the unbound Stage 3 prerequisite services. */
public final class ChunkPrerequisiteServices {
    private final WorldId world;
    private final ChunkCustodyModel custody = new ChunkCustodyModel();
    private final ChunkLifecycleRegistry lifecycles;
    private final ChunkWorkRegistry works;
    private final PoiInstallService poi;
    private final LightInputService light;
    private final ChunkTypeMetadataService metadata;
    private final LoadApplyPrerequisites prerequisites;
    public ChunkPrerequisiteServices(WorldId world, String serverSession) {
        this(world, serverSession, 0);
    }
    public ChunkPrerequisiteServices(WorldId world, String serverSession, long worldInstanceSequence) {
        this.world = Objects.requireNonNull(world, "world"); lifecycles = new ChunkLifecycleRegistry(custody, serverSession); works = new ChunkWorkRegistry(custody);
        poi = new PoiInstallService(lifecycles, works);
        light = new LightInputService(custody, works, world, serverSession, worldInstanceSequence);
        metadata = new ChunkTypeMetadataService(custody, works, world, serverSession, worldInstanceSequence);
        prerequisites = new LoadApplyPrerequisites(lifecycles, works, poi, light, metadata);
    }
    public WorldId world() { return world; } public ChunkCustodyModel custody() { return custody; } public ChunkLifecycleRegistry lifecycles() { return lifecycles; }
    public ChunkWorkRegistry works() { return works; } public PoiInstallService poi() { return poi; } public LightInputService light() { return light; }
    public ChunkTypeMetadataService metadata() { return metadata; } public LoadApplyPrerequisites prerequisites() { return prerequisites; }
    public void retire(ChunkCustodyModel.LifecycleId lifecycle) { poi.retire(lifecycle); light.retire(lifecycle); metadata.retire(lifecycle); }
    public void shutdown() { custody.closeAdmissions(); prerequisites.shutdown(); poi.shutdown(); light.shutdown(); metadata.shutdown(); }
    public void releaseFinishedWork(ChunkCustodyModel.WorkId work, Object holderEvidence) {
        Objects.requireNonNull(work); Objects.requireNonNull(holderEvidence);
        ChunkCustodyModel.LifecycleId lifecycle = work.lifecycle();
        if (!lifecycles.holderMatches(lifecycle, holderEvidence))
            throw new ChunkProtocolException("finished work lost exact holder evidence");
        if (custody.lifecycleState(lifecycle).orElse(null) != ChunkCustodyModel.LifecycleState.RETIRED
                && !lifecycles.retireHolder(lifecycle, holderEvidence))
            throw new ChunkProtocolException("finished work could not retire holder lifecycle");
        releaseRetiredLifecycle(lifecycle);
    }
    /** Explicit final consumer acknowledgment; never called while its synchronous claim is active. */
    public void releaseRetiredLifecycle(ChunkCustodyModel.LifecycleId lifecycle) {
        if (!custody.canReleaseRetiredLifecycle(lifecycle)) throw new ChunkProtocolException("world-service cleanup before terminal acknowledgment");
        retire(lifecycle); works.releaseRetired(lifecycle); lifecycles.releaseRetired(lifecycle); custody.releaseRetiredLifecycle(lifecycle);
    }
    public int receiptEntries() { return poi.entries() + light.entries(); }
}
