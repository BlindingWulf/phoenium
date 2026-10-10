package dev.phoenium.engine.mixin;

import dev.phoenium.engine.EngineAutosave;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-E12 (engine mode only): a spreading autosave's ChunkMap.saveAllChunks(false) clears the save cooldowns as vanilla
 * does and records the visible holders' keys; each ChunkMap.tick(haveTime) after it (where vanilla saves eagerly) runs
 * vanilla's saveChunkIfNeeded for the next keys ({@link EngineAutosave}). Any other saveAllChunks (a flush, a full save
 * not from a spreading autosave) runs as vanilla and drops the queue.
 *
 * <p>S9-E15: each key's outcome is counted with saveChunkIfNeeded's own conditions, in its order (no visible holder, not
 * accessible since the last save, not ready or not a level/imposter chunk, nothing unsaved, inside the 10 s cooldown);
 * those read-only checks return what saveChunkIfNeeded would return, and the eligible keys go through it as before.
 */
@Mixin(ChunkMap.class)
abstract class EngineAutosaveChunksMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Long2LongMap nextChunkSaveTime;
    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Unique private final EngineAutosave.Spread phoenium$saves = new EngineAutosave.Spread();

    @Shadow private boolean saveChunkIfNeeded(ChunkHolder chunk, long now) { throw new AssertionError(); }

    @Shadow protected abstract ChunkHolder getVisibleChunkIfPresent(long key);

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void phoenium$recordChunkSaves(boolean flushStorage, CallbackInfo ci) {
        if (!flushStorage && EngineAutosave.recordChunks()) {
            this.nextChunkSaveTime.clear();
            EngineAutosave.recordedChunks(this.phoenium$saves, this.visibleChunkMap.keySet());
            ci.cancel();
            return;
        }
        EngineAutosave.fullSaveChunks(this.phoenium$saves);
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void phoenium$drainChunkSaves(BooleanSupplier haveTime, CallbackInfo ci) {
        if (!this.phoenium$saves.busy() || this.level.noSave()) return;
        long now = Util.getMillis();
        EngineAutosave.drainChunks(this.phoenium$saves, key -> {
            ChunkHolder holder = this.getVisibleChunkIfPresent(key);
            if (holder == null) return EngineAutosave.chunkOutcome(EngineAutosave.CHUNK_GONE);
            if (!holder.wasAccessibleSinceLastSave()) return EngineAutosave.chunkOutcome(EngineAutosave.CHUNK_IDLE);
            ChunkAccess chunk = holder.isReadyForSaving() ? holder.getLatestChunk() : null;
            if (!(chunk instanceof ImposterProtoChunk) && !(chunk instanceof LevelChunk)) {
                return EngineAutosave.chunkOutcome(EngineAutosave.CHUNK_NOT_READY);
            }
            if (!chunk.isUnsaved()) return EngineAutosave.chunkOutcome(EngineAutosave.CHUNK_CLEAN);
            if (now < this.nextChunkSaveTime.getOrDefault(key, -1L)) return EngineAutosave.chunkOutcome(EngineAutosave.CHUNK_COOLDOWN);
            return EngineAutosave.chunkOutcome(this.saveChunkIfNeeded(holder, now) ? EngineAutosave.CHUNK_SAVED : EngineAutosave.CHUNK_NOT_WRITTEN);
        });
    }
}
