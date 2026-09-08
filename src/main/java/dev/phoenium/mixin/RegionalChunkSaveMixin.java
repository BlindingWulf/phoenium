package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.phoenium.Phoenium;
import dev.phoenium.save.RegionalSaveBinding;
import dev.phoenium.save.RetainedChunkSnapshot;
import dev.phoenium.chunk.ChunkSaveRetention;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The original snapshot is produced once, then its remaining saved-tag aliases are
 * detached before the original background encoder can receive the data. */
@Mixin(ChunkMap.class)
abstract class RegionalChunkSaveMixin implements RegionalSaveBinding.MapAccess {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
    @Unique private final java.util.IdentityHashMap<ChunkHolder, ChunkSaveRetention> phoenium$visibleSaves = new java.util.IdentityHashMap<>();

    @Override public ChunkHolder phoenium$pendingUnload(long position) { return pendingUnloads.get(position); }
    @Override public ChunkHolder phoenium$visibleSaveHolder(long position) { return visibleChunkMap.get(position); }
    @Override public void phoenium$retainVisibleSave(ChunkHolder holder, ChunkSaveRetention retention) {
        var previous = phoenium$visibleSaves.put(holder, retention);
        if (previous != null) previous.close();
    }
    @Override public ChunkSaveRetention phoenium$borrowVisibleSave(ChunkHolder holder, ChunkAccess selected) {
        var retained = phoenium$visibleSaves.get(holder);
        if (retained == null) return null;
        if (visibleChunkMap.get(holder.getPos().pack()) != holder || !retained.matches(selected))
            throw new IllegalStateException("visible save selection lost its retained original holder");
        return retained.fork();
    }
    @Inject(method = "promoteChunkMap", at = @At("RETURN"), require = 1, expect = 1)
    private void phoenium$releaseWithdrawnVisibleSaves(CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValue()) return;
        var iterator = phoenium$visibleSaves.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (visibleChunkMap.get(entry.getKey().getPos().pack()) != entry.getKey()) {
                var retained = entry.getValue();
                iterator.remove(); retained.close();
            }
        }
    }
    @Inject(method = "close", at = @At("HEAD"), require = 1, expect = 1)
    private void phoenium$closeVisibleSaves(CallbackInfo ci) {
        var retained = java.util.List.copyOf(phoenium$visibleSaves.values());
        phoenium$visibleSaves.clear();
        retained.forEach(ChunkSaveRetention::close);
    }

    @WrapMethod(method = "saveAllChunks")
    private void phoenium$saveBatch(boolean flush, Operation<Void> original) {
        var coordinator = Phoenium.liveRuntime().regionalSaves();
        if (coordinator == null) { original.call(flush); return; }
        RegionalSaveBinding.batch(coordinator, (ChunkMap) (Object) this, level, flush, () -> original.call(flush));
    }

    @WrapOperation(method = "saveAllChunks",
            at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;toList()Ljava/util/List;"), require = 1, expect = 1)
    private List<ChunkHolder> phoenium$readySaveBatch(Stream<ChunkHolder> stream, Operation<List<ChunkHolder>> original) {
        List<ChunkHolder> selected = original.call(stream);
        return Phoenium.liveRuntime().regionalSaves() == null ? selected
                : RegionalSaveBinding.readyBatch((ChunkMap) (Object) this, selected);
    }

    @WrapOperation(method = {"saveAllChunks", "isExistingChunkFull"},
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;"),
            require = 2, expect = 2)
    private Object phoenium$originalStorageWait(CompletableFuture<?> future, Operation<Object> original) {
        return Phoenium.liveRuntime().regionalSaves() == null ? original.call(future)
                : dev.phoenium.chunk.FullFinishingServices.ownerFreeIo(() -> original.call(future));
    }

    @WrapMethod(method = "saveChunkIfNeeded")
    private boolean phoenium$selectedSave(ChunkHolder holder, long now, Operation<Boolean> original) {
        if (Phoenium.liveRuntime().regionalSaves() == null) return original.call(holder, now);
        return RegionalSaveBinding.selectionScope((ChunkMap) (Object) this, () -> original.call(holder, now));
    }

    @WrapMethod(method = "saveChunksEagerly")
    private void phoenium$eagerSelection(BooleanSupplier haveTime, Operation<Void> original) {
        if (Phoenium.liveRuntime().regionalSaves() == null) { original.call(haveTime); return; }
        RegionalSaveBinding.selectionScope((ChunkMap) (Object) this, () -> { original.call(haveTime); return null; });
    }

    @WrapOperation(method = {"saveChunkIfNeeded", "saveChunksEagerly", "lambda$saveAllChunks$0"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getLatestChunk()Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            require = 3, expect = 3)
    private ChunkAccess phoenium$originalSelectedHolder(ChunkHolder holder, Operation<ChunkAccess> original) {
        var coordinator = Phoenium.liveRuntime().regionalSaves();
        if (coordinator == null) return original.call(holder);
        return RegionalSaveBinding.select(coordinator, (ChunkMap) (Object) this, level, holder, () -> original.call(holder));
    }

    @WrapOperation(method = {"saveChunkIfNeeded", "saveChunksEagerly"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;isUnsaved()Z"), require = 2, expect = 2)
    private boolean phoenium$selectedDirtyRead(ChunkAccess chunk, Operation<Boolean> original) {
        var coordinator = Phoenium.liveRuntime().regionalSaves();
        return coordinator == null ? original.call(chunk)
                : RegionalSaveBinding.isUnsaved(coordinator, (ChunkMap) (Object) this, level, chunk, () -> original.call(chunk));
    }

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
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getPersistedStatus()Lnet/minecraft/world/level/chunk/status/ChunkStatus;"), require = 1, expect = 1)
    private ChunkStatus phoenium$sourceStatus(ChunkAccess chunk, Operation<ChunkStatus> original) {
        return Phoenium.liveRuntime().regionalSaves() == null ? original.call(chunk)
                : RegionalSaveBinding.readSource(chunk, () -> original.call(chunk));
    }

    @WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;noneMatch(Ljava/util/function/Predicate;)Z"), require = 1, expect = 1)
    private boolean phoenium$sourceStructures(Stream<StructureStart> stream, Predicate<StructureStart> predicate,
                                               Operation<Boolean> original) {
        return Phoenium.liveRuntime().regionalSaves() == null ? original.call(stream, predicate)
                : RegionalSaveBinding.readSource(() -> original.call(stream, predicate));
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

    @WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;reportChunkSaveFailure(Ljava/lang/Throwable;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;)V"),
            require = 1, expect = 1)
    private void phoenium$reportedSaveFailure(MinecraftServer server, Throwable failure, RegionStorageInfo storage,
                                              ChunkPos pos, Operation<Void> original) {
        if (Phoenium.liveRuntime().regionalSaves() != null)
            RegionalSaveBinding.reportedFailure((ChunkMap) (Object) this, pos, failure);
        original.call(server, failure, storage, pos);
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
