package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only observation of the POI/section-storage branch. */
@Mixin(SectionStorage.class)
abstract class ChunkRuntimeTraceSectionStorageMixin {
    @Inject(method = "prefetch(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private void phoenium$poiPrefetchStarted(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        ChunkRuntimeTrace.stageByTarget("POI_PREFETCH_ENTER", pos, null, null, null);
    }

    @Inject(method = "prefetch(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"), require = 1)
    private void phoenium$poiPrefetchReturned(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        ChunkRuntimeTrace.stageByTarget("POI_PREFETCH_RETURN", pos, null, cir.getReturnValue(), null);
    }

    @Inject(method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"), require = 1)
    private void phoenium$poiUnpackStarted(ChunkPos pos, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("POI_UNPACK_PENDING_ENTER", pos, null, null, this);
    }

    @Inject(method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("RETURN"), require = 1)
    private void phoenium$poiUnpackReturned(ChunkPos pos, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("POI_UNPACK_PENDING_RETURN", pos, null, null, this);
    }

    @Inject(method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/SectionStorage$PackedChunk;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$poiUnpackDataStarted(ChunkPos pos, @Coerce Object packed, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("POI_UNPACK_DATA_ENTER", pos, null, packed, this);
    }

    @Inject(method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/SectionStorage$PackedChunk;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$poiUnpackDataReturned(ChunkPos pos, @Coerce Object packed, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("POI_UNPACK_DATA_RETURN", pos, null, packed, this);
    }
}
