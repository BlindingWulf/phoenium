package dev.phoenium.chunk;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Read-only exact provider publication receipt. Consumer topology/route claims remain
 * with the gameplay operation; this receipt never establishes ownership or loads chunks. */
public sealed interface ResidentChunkSourceBinding {
    /** Pins registration to the current provider epoch even before a FULL chunk is visible. */
    final class Registration {
        private final ChunkHolder holder;
        private final ChunkHolderLifetime lifetime;
        private final C2meResidentPublication provider;
        public Registration(ChunkHolder holder, ChunkHolderLifetime lifetime) {
            this.holder = holder; this.lifetime = lifetime;
            provider = lifetime.services().residentPublication(lifetime);
        }
        public boolean current() {
            return BlockEntityExternalReadPreparation.current(holder, lifetime)
                && lifetime.services().residentPublication(lifetime) == provider
                && (provider == null || provider.pendingCurrent());
        }
    }
    ServerLevel world();
    long key();
    ChunkHolder holder();
    ChunkHolderLifetime lifetime();
    LevelChunk chunk();
    boolean providerRuntimeOpen();
    boolean providerCurrent();
    String provider();
    default String observation() { return "provider=" + provider() + " providerRuntimeOpen=" + providerRuntimeOpen()
        + " holderLifetimeMatch=" + BlockEntityExternalReadPreparation.current(holder(), lifetime())
        + " providerFullStateCurrent=" + providerCurrent(); }

    default boolean current() {
        return providerRuntimeOpen()
            && BlockEntityExternalReadPreparation.visible(world(), key()) == holder()
            && BlockEntityExternalReadPreparation.current(holder(), lifetime())
            && chunk().getLevel() == world() && chunk().getPos().pack() == key()
            && providerCurrent();
    }

    static ResidentChunkSourceBinding capture(ServerLevel level, long key) {
        var holder = BlockEntityExternalReadPreparation.visible(level, key);
        var lifetime = BlockEntityExternalReadPreparation.lifetime(holder);
        if (!BlockEntityExternalReadPreparation.current(holder, lifetime)) return null;
        var provider = lifetime.services().residentPublication(lifetime);
        ResidentChunkSourceBinding binding;
        if (provider != null) {
            var chunk = provider.chunk();
            if (chunk == null) return null;
            binding = new C2me(level, key, holder, lifetime, chunk, provider);
        } else {
            var slot = ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex());
            var chunk = published(slot);
            if (chunk == null) return null;
            var runtime = ((FullGenerationSource) level.getChunkSource().chunkMap).phoenium$fullRegistration();
            if (runtime == null) return null;
            binding = new Vanilla(level, key, holder, lifetime, chunk, slot, runtime);
        }
        return binding.current() ? binding : null;
    }

    private static LevelChunk published(CompletableFuture<ChunkResult<ChunkAccess>> slot) {
        if (slot == null || !slot.isDone() || slot.isCompletedExceptionally()) return null;
        var result = slot.getNow(null);
        var chunk = result == null ? null : result.orElse(null);
        if (chunk instanceof ImposterProtoChunk imposter) chunk = imposter.getWrapped();
        return chunk instanceof LevelChunk full ? full : null;
    }

    record Vanilla(ServerLevel world, long key, ChunkHolder holder, ChunkHolderLifetime lifetime,
                   LevelChunk chunk, CompletableFuture<ChunkResult<ChunkAccess>> slot,
                   FullRegistrationRuntime runtime) implements ResidentChunkSourceBinding {
        public String provider() { return "VANILLA"; }
        public boolean providerRuntimeOpen() {
            return runtime.naturalSpawnOpen()
                && ((FullGenerationSource) world.getChunkSource().chunkMap).phoenium$fullRegistration() == runtime;
        }
        public boolean providerCurrent() {
            return ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex()) == slot
                && published(slot) == chunk;
        }
    }

    record C2me(ServerLevel world, long key, ChunkHolder holder, ChunkHolderLifetime lifetime,
                LevelChunk chunk, C2meResidentPublication publication) implements ResidentChunkSourceBinding {
        public String provider() { return "C2ME"; }
        public String observation() { return publication.observation() + " holderLifetimeMatch="
            + BlockEntityExternalReadPreparation.current(holder, lifetime); }
        public boolean providerRuntimeOpen() { return publication.runtimeOpen(); }
        public boolean providerCurrent() {
            return publication.matches(world, holder) && lifetime.services().residentPublication(lifetime) == publication && publication.chunk() == chunk;
        }
    }
}
