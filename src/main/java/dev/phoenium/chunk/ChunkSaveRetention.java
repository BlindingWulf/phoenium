package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;

/** The original selected holder lends its exact data to an in-flight save. This is
 * independent of FULL publication eligibility: normal unload may already withdraw
 * that subscriber while its original final save is still required. */
public final class ChunkSaveRetention implements AutoCloseable {
    private final Shared shared;
    private boolean closed;
    private ChunkSaveRetention(Shared shared) { this.shared = shared; }

    public static ChunkSaveRetention retain(ChunkBindingRuntime.WorldAuthority authority,
                                             GenerationChunkHolder holder, ChunkAccess selected) {
        java.util.Objects.requireNonNull(selected);
        var active = OwnershipContext.current().orElseThrow(() -> new ChunkProtocolException("save source retained without its owner"));
        ChunkHolderLifetime lifetime = ((FullHolderEvidence) holder).phoenium$lifetime();
        if (lifetime == null || !sameChunk(holder.getLatestChunk(), selected))
            throw new ChunkProtocolException("save source is not the original selected holder result");
        var route = authority.directory().resolve(lifetime.lifecycle().chunkKey()).orElseThrow();
        if (!active.region().equals(route.region()) || authority.services() != lifetime.services()
                || authority.worldInstanceSequence() != lifetime.lifecycle().worldInstanceSequence())
            throw new ChunkProtocolException("save retention has a foreign world, lifetime or regional lease");
        authority.leases().get(route.region()).requireCurrent(active.lease());
        var services = lifetime.services();
        var custody = services.custody();
        custody.gate().lock();
        try {
            if (!services.lifecycles().isCurrent(lifetime.lifecycle())
                    || !services.lifecycles().holderMatches(lifetime.lifecycle(), holder))
                throw new ChunkProtocolException("save source holder lifetime is no longer current");
            custody.retainLifecycle(lifetime.lifecycle());
        } finally { custody.gate().unlock(); }
        return new ChunkSaveRetention(new Shared(lifetime, selected));
    }

    /** An already retained original unload may grant its final save a separate use,
     * even after the original conditional map removal retired public availability. */
    public ChunkSaveRetention fork() {
        requireOpen();
        shared.uses = Math.incrementExact(shared.uses);
        return new ChunkSaveRetention(shared);
    }

    public boolean matches(ChunkAccess selected) {
        requireOpen();
        var lifetime = shared.lifetime;
        return sameChunk(shared.selected, selected)
                && lifetime.services().lifecycles().holderMatches(lifetime.lifecycle(), lifetime.holder())
                && sameChunk(lifetime.holder().getLatestChunk(), shared.selected);
    }

    public boolean current() {
        requireOpen();
        return shared.lifetime.services().lifecycles().isCurrent(shared.lifetime.lifecycle())
                && matches(shared.selected);
    }

    @Override public void close() {
        if (Thread.currentThread() != shared.thread) throw new ChunkProtocolException("save custody released by an I/O producer");
        if (closed) return;
        closed = true;
        if (--shared.uses != 0) return;
        var services = shared.lifetime.services();
        services.custody().acknowledgeLifecycle(shared.lifetime.lifecycle());
        services.tryReleaseRetiredLifecycle(shared.lifetime.lifecycle());
    }

    private void requireOpen() {
        if (Thread.currentThread() != shared.thread) throw new ChunkProtocolException("save source custody accessed from its I/O producer");
        if (closed || shared.uses <= 0) throw new ChunkProtocolException("expired save source retention");
    }
    public static boolean sameChunk(ChunkAccess left, ChunkAccess right) {
        return actual(left) == actual(right);
    }
    private static ChunkAccess actual(ChunkAccess chunk) {
        return chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
    }
    private static final class Shared {
        final ChunkHolderLifetime lifetime;
        final ChunkAccess selected;
        final Thread thread = Thread.currentThread();
        int uses = 1;
        Shared(ChunkHolderLifetime lifetime, ChunkAccess selected) { this.lifetime = lifetime; this.selected = selected; }
    }
}
