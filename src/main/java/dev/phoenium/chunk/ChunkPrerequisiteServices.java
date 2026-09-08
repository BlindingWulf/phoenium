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
    private FullPublishedInputs published;
    private final java.util.Map<ChunkCustodyModel.LifecycleId, ChunkHolderLifetime> residentHolders = new java.util.HashMap<>();
    public void trackHolder(ChunkCustodyModel.LifecycleId lifecycle, net.minecraft.server.level.GenerationChunkHolder holder) {
        custody.gate().lock(); try {
            var lifetime = residentHolders.computeIfAbsent(lifecycle, ignored -> new ChunkHolderLifetime(this, lifecycle, holder));
            if (lifetime.holder() != holder) throw new ChunkProtocolException("holder lifetime identity changed");
            ((FullHolderEvidence) holder).phoenium$lifetime(lifetime);
        } finally { custody.gate().unlock(); }
    }
    void retireResidentHolder(ChunkHolderLifetime lifetime) {
        custody.gate().lock(); try {
            if (residentHolders.get(lifetime.lifecycle()) != lifetime) return;
            if (custody.lifecycleState(lifetime.lifecycle()).orElse(null) != ChunkCustodyModel.LifecycleState.RETIRED)
                lifecycles.retireHolder(lifetime.lifecycle(), lifetime.holder());
        } finally { custody.gate().unlock(); }
        var failures = new FullInputNotifications.Failures();
        failures.run(() -> FullPublishedInputs.retired(lifetime.holder()));
        failures.run(() -> tryReleaseRetiredLifecycle(lifetime.lifecycle()));
        failures.rethrow();
    }
    public void stopResidentHolders() {
        var failures = new FullInputNotifications.Failures();
        failures.run(this::stopPublishedInputs);
        java.util.List<ChunkHolderLifetime> snapshot;
        custody.gate().lock(); try { snapshot = java.util.List.copyOf(residentHolders.values()); } finally { custody.gate().unlock(); }
        for (var holder : snapshot) failures.run(() -> retireResidentHolder(holder));
        failures.rethrow();
    }
    public int residentHolderEntries() { custody.gate().lock(); try { return residentHolders.size(); } finally { custody.gate().unlock(); } }
    public FullPublishedInputs publishedInputs(net.minecraft.server.level.ServerLevel level, ChunkBindingRuntime.WorldAuthority authority) {
        custody.gate().lock(); try {
            if (published == null) published = new FullPublishedInputs(level, authority);
            return published;
        } finally { custody.gate().unlock(); }
    }
    public void stopPublishedInputs() {
        FullPublishedInputs current;
        custody.gate().lock(); try { current = published; } finally { custody.gate().unlock(); }
        if (current != null) current.shutdown();
    }
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
    public void shutdown() {
        var failures = new FullInputNotifications.Failures();
        failures.run(custody::closeAdmissions);
        failures.run(this::stopResidentHolders);
        failures.run(prerequisites::shutdown);
        failures.run(poi::shutdown);
        failures.run(light::shutdown);
        failures.run(metadata::shutdown);
        failures.rethrow();
    }
    /** Only the completed execution leaves; the exact resident holder and other work survive. */
    public void settleFinishedExecution(ChunkCustodyModel.WorkId work, Object holderEvidence) {
        if (!lifecycles.holderMatches(work.lifecycle(), holderEvidence)) throw new ChunkProtocolException("settled execution lost holder evidence");
        if (custody.hasActiveUse(work) || custody.hasActiveDemand(work)) throw new ChunkProtocolException("execution still has unacknowledged use");
        works.retireWork(work, false);
        works.releaseRetiredWork(work);
        tryReleaseRetiredLifecycle(work.lifecycle());
    }
    public void releaseFinishedWork(ChunkCustodyModel.WorkId work, Object holderEvidence) {
        Objects.requireNonNull(work); Objects.requireNonNull(holderEvidence);
        ChunkCustodyModel.LifecycleId lifecycle = work.lifecycle();
        if (!lifecycles.holderMatches(lifecycle, holderEvidence))
            throw new ChunkProtocolException("finished work lost exact holder evidence");
        if (custody.lifecycleState(lifecycle).orElse(null) != ChunkCustodyModel.LifecycleState.RETIRED
                && !lifecycles.retireHolder(lifecycle, holderEvidence))
            throw new ChunkProtocolException("finished work could not retire holder lifecycle");
        works.releaseRetiredWork(work);
        tryReleaseRetiredLifecycle(lifecycle);
    }
    /** A terminal graph may acknowledge before another graph or retained publication in this holder. */
    void tryReleaseRetiredLifecycle(ChunkCustodyModel.LifecycleId lifecycle) {
        custody.gate().lock(); try {
            if (custody.canReleaseRetiredLifecycle(lifecycle) && !works.hasRetainedWork(lifecycle)) releaseRetiredLifecycle(lifecycle);
        } finally { custody.gate().unlock(); }
    }
    /** Explicit final consumer acknowledgment; never called while its synchronous claim is active. */
    public void releaseRetiredLifecycle(ChunkCustodyModel.LifecycleId lifecycle) {
        custody.gate().lock(); try {
            if (!custody.canReleaseRetiredLifecycle(lifecycle)) throw new ChunkProtocolException("world-service cleanup before terminal acknowledgment");
            retire(lifecycle); works.releaseRetired(lifecycle); lifecycles.releaseRetired(lifecycle); custody.releaseRetiredLifecycle(lifecycle);
            var lifetime = residentHolders.remove(lifecycle);
            if (lifetime != null && ((FullHolderEvidence) lifetime.holder()).phoenium$lifetime() == lifetime)
                ((FullHolderEvidence) lifetime.holder()).phoenium$lifetime(null);
        } finally { custody.gate().unlock(); }
    }
    public int receiptEntries() { return poi.entries() + light.entries(); }
}
