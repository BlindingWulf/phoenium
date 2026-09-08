package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.phoenium.Phoenium;
import dev.phoenium.save.RegionalSaveBinding;
import dev.phoenium.save.RetainedChunkSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;

/** The original snapshot is produced once, then its remaining saved-tag aliases are
 * detached before the original background encoder can receive the data. */
@Mixin(ChunkMap.class)
abstract class RegionalChunkSaveMixin implements RegionalSaveBinding.MapAccess {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Override public ChunkHolder phoenium$saveHolder(long position) {
        ChunkHolder current = updatingChunkMap.get(position);
        return current != null ? current : pendingUnloads.get(position);
    }
    @Override public ChunkHolder phoenium$pendingUnload(long position) { return pendingUnloads.get(position); }

    @WrapMethod(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z")
    private boolean phoenium$saveSource(ChunkAccess chunk, Operation<Boolean> original) {
        var coordinator = Phoenium.liveRuntime().regionalSaves();
        // Pre-start bootstrap precedes installation of regional services. Once installed,
        // every selected save remains on this protocol, including normal server stop.
        if (coordinator == null) return original.call(chunk);
        return RegionalSaveBinding.save(coordinator, (ChunkMap) (Object) this, level, chunk, () -> original.call(chunk));
    }

    @WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;tryMarkSaved()Z"), require = 1, expect = 1)
    private boolean phoenium$claimDirty(ChunkAccess chunk, Operation<Boolean> original) {
        if (Phoenium.liveRuntime().regionalSaves() == null) return original.call(chunk);
        return RegionalSaveBinding.markSaved(chunk, () -> original.call(chunk));
    }

    @WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;"),
            require = 1, expect = 1)
    private SerializableChunkData phoenium$retainSnapshot(ServerLevel level, ChunkAccess chunk,
                                                         Operation<SerializableChunkData> original) {
        if (Phoenium.liveRuntime().regionalSaves() == null)
            return RetainedChunkSnapshot.detach(original.call(level, chunk));
        return RegionalSaveBinding.capture(level, chunk, () -> original.call(level, chunk));
    }

    @WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;write(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"), require = 1, expect = 1)
    private CompletableFuture<Void> phoenium$writeAcknowledgement(ChunkMap map, ChunkPos pos,
            Supplier<CompoundTag> encoded, Operation<CompletableFuture<Void>> original) {
        if (Phoenium.liveRuntime().regionalSaves() == null) return original.call(map, pos, encoded);
        return RegionalSaveBinding.write(map, pos, () -> original.call(map, pos, encoded));
    }

    @WrapMethod(method = "lambda$scheduleUnload$0")
    private void phoenium$retainUnload(ChunkHolder holder, CompletableFuture<?> expectedSave, long position,
                                       Operation<Void> original) {
        var coordinator = Phoenium.liveRuntime().regionalSaves();
        if (coordinator == null) { original.call(holder, expectedSave, position); return; }
        RegionalSaveBinding.unload(coordinator, (ChunkMap) (Object) this, level, holder, expectedSave, position,
                () -> original.call(holder, expectedSave, position));
    }

    @WrapOperation(method = "lambda$scheduleUnload$0",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setLoaded(Z)V"), require = 1, expect = 1)
    private void phoenium$loadedFlag(LevelChunk chunk, boolean loaded, Operation<Void> original) {
        var coordinator = Phoenium.liveRuntime().regionalSaves();
        if (coordinator == null) { original.call(chunk, loaded); return; }
        RegionalSaveBinding.mutateUnload(coordinator, level, chunk, () -> original.call(chunk, loaded));
    }

    @WrapOperation(method = "lambda$scheduleUnload$0",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V"), require = 1, expect = 1)
    private void phoenium$unloadBody(ServerLevel sourceLevel, LevelChunk chunk, Operation<Void> original) {
        var coordinator = Phoenium.liveRuntime().regionalSaves();
        if (coordinator == null) { original.call(sourceLevel, chunk); return; }
        RegionalSaveBinding.mutateUnload(coordinator, sourceLevel, chunk, () -> original.call(sourceLevel, chunk));
    }
}
