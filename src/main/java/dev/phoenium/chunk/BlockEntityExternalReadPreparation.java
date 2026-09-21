package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.mixin.RegionalServerChunkCacheAccess;
import dev.phoenium.ownership.OwnershipContext;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** One reached FULL/create=true read, with no block-state snapshot or private FULL
 * authority. Native selection and waiting happen only after the source owner returns.
 * Completion publishes metadata only; the getter itself runs under the resumed owner. */
public final class BlockEntityExternalReadPreparation implements AutoCloseable {
    public record Identity(ServerLevel world, BlockPos position, long chunkKey,
                           TopologyGeneration topology, long tick, long query) { }
    private final Identity identity;
    private final BooleanSupplier valid;
    private final Runnable ownerFree;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private ChunkHolder holder;
    private ChunkHolderLifetime lifetime;
    private CompletableFuture<ChunkResult<ChunkAccess>> selected;
    private ResidentChunkSourceBinding binding;
    private C2meResidentPublication provider;
    private CompletableFuture<ChunkResult<ChunkAccess>> vanillaSlot;
    private boolean closed, stale;

    public BlockEntityExternalReadPreparation(ServerLevel level, BlockPos position,
            TopologyGeneration topology, long tick, long query, BooleanSupplier valid, Runnable ownerFree) {
        if (tick <= 0 || query <= 0) throw new IllegalArgumentException("invalid Vault read identity");
        identity = new Identity(level, position.immutable(), ChunkPos.pack(position), topology, tick, query);
        this.valid = Objects.requireNonNull(valid); this.ownerFree = Objects.requireNonNull(ownerFree);
        holder = visible(level, identity.chunkKey); lifetime = lifetime(holder);
        provider = lifetime == null ? null : lifetime.services().residentPublication(lifetime);
        observeVanillaSlot();
    }
    private void observeVanillaSlot() {
        if (provider == null && holder != null && vanillaSlot == null
                && ((FullGenerationSource) identity.world.getChunkSource().chunkMap).phoenium$fullRegistration() != null)
            vanillaSlot = ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex());
    }
    public Identity identity() { return identity; }
    public CompletableFuture<Void> dependency() { return ready; }
    public static ChunkHolder visible(ServerLevel level, long key) {
        return ((FullInputView) level.getChunkSource().chunkMap).phoenium$visibleInput(key);
    }
    public static ChunkHolderLifetime lifetime(ChunkHolder holder) {
        return holder == null ? null : ((FullHolderEvidence) holder).phoenium$lifetime();
    }
    public static boolean current(ChunkHolder holder, ChunkHolderLifetime lifetime) {
        return holder != null && lifetime != null && lifetime.holder() == holder
            && lifetime(holder) == lifetime && lifetime.services().lifecycles().isCurrent(lifetime.lifecycle());
    }
    /** Observation only: never selects or waits on a native future under the owner. */
    public static boolean ready(ServerLevel level, BlockPos position) {
        if (level.isOutsideBuildHeight(position)) return true; // native getBlockState returns VOID_AIR here
        return ResidentChunkSourceBinding.capture(level, ChunkPos.pack(position)) != null;
    }
    public boolean current() {
        if (closed || stale || !valid.getAsBoolean()) return false;
        var now = visible(identity.world, identity.chunkKey);
        if (holder == null) return true; // bind the first published holder at the next owner-free observation
        return now == holder && (lifetime == null || current(holder, lifetime))
            && (provider == null || lifetime.services().residentPublication(lifetime) == provider && provider.pendingCurrent())
            && (vanillaSlot == null || provider == null
                && ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex()) == vanillaSlot)
            && (binding == null || binding.current());
    }
    public void prepare() {
        ownerFree.run();
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("Vault preparation retained owner");
        if (closed || ready.isDone() || !valid.getAsBoolean()) return;
        try {
            var now = visible(identity.world, identity.chunkKey);
            if (holder != null && (now != holder || lifetime != null && !current(holder, lifetime))) { stale = true; return; }
            if (!current()) { stale = true; return; }
            if (selected == null) {
                binding = ResidentChunkSourceBinding.capture(identity.world, identity.chunkKey);
                if (binding != null) {
                    holder = binding.holder(); lifetime = binding.lifetime();
                    ready.complete(null); return;
                }
                var pos = ChunkPos.unpack(identity.chunkKey);
                selected = ((RegionalServerChunkCacheAccess) identity.world.getChunkSource())
                    .phoenium$naturalSpawnFuture(pos.x(), pos.z(), ChunkStatus.FULL, true);
            }
            now = visible(identity.world, identity.chunkKey);
            if (holder == null) { holder = now; lifetime = lifetime(holder); }
            if (holder == null) return;
            if (now != holder) { stale = true; return; }
            if (lifetime == null) lifetime = lifetime(holder);
            if (lifetime == null) return; // publication may attach the lifecycle on a later native poll
            var nowProvider = lifetime.services().residentPublication(lifetime);
            if (provider == null) provider = nowProvider;
            observeVanillaSlot();
            if (!current()) { stale = true; return; }
            if (!selected.isDone()) return;
            var result = selected.join().orElse(null);
            if (result == null) throw new ChunkProtocolException("Vault FULL dependency unloaded: " + identity);
            if (result instanceof ImposterProtoChunk imposter) result = imposter.getWrapped();
            if (binding == null) binding = ResidentChunkSourceBinding.capture(identity.world, identity.chunkKey);
            if (binding == null) return;
            if (!binding.current()) { stale = true; return; }
            if (result != binding.chunk())
                throw new ChunkProtocolException("Vault FULL receipt changed its native result: " + identity);
            ready.complete(null);
        } catch (CompletionException failure) {
            ready.completeExceptionally(failure.getCause() == null ? failure : failure.getCause());
        } catch (RuntimeException | Error failure) { ready.completeExceptionally(failure); }
    }
    public boolean prepared() { return ready.isDone() && !ready.isCompletedExceptionally() && current(); }
    @Override public void close() {
        // Do not cancel the shared native future or turn stale retirement into a
        // failed fence callback. The reservation observes current()==false and retires.
        closed = true; selected = null; vanillaSlot = null; binding = null; provider = null; holder = null; lifetime = null;
    }
}
