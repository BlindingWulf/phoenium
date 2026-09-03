package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only observation of light-engine state submission and lighting completion. */
@Mixin(ThreadedLevelLightEngine.class)
abstract class ChunkRuntimeTraceLightEngineMixin {
    @Inject(method = "retainData(Lnet/minecraft/world/level/ChunkPos;Z)V", at = @At("HEAD"), require = 1)
    private void phoenium$lightRetainData(ChunkPos pos, boolean retain, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("LIGHT_ENGINE_RETAIN_DATA_ENTER", pos,
            "retain=" + retain, null, this);
    }

    @Inject(method = "retainData(Lnet/minecraft/world/level/ChunkPos;Z)V", at = @At("RETURN"), require = 1)
    private void phoenium$lightRetainDataReturn(ChunkPos pos, boolean retain, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("LIGHT_ENGINE_RETAIN_DATA_RETURN", pos,
            "retain=" + retain, null, this);
    }

    @Inject(method = "queueSectionData(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/DataLayer;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$lightQueueSectionData(LightLayer layer, SectionPos section,
                                                 DataLayer data, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("LIGHT_ENGINE_QUEUE_SECTION_DATA_ENTER", section.chunk(),
            layer.toString(), data, this);
    }

    @Inject(method = "queueSectionData(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/DataLayer;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$lightQueueSectionDataReturn(LightLayer layer, SectionPos section,
                                                       DataLayer data, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("LIGHT_ENGINE_QUEUE_SECTION_DATA_RETURN", section.chunk(),
            layer.toString(), data, this);
    }

    @Inject(method = "lightChunk(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private void phoenium$lightChunkStarted(ChunkAccess chunk, boolean lit,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkRuntimeTrace.stageByTarget("LIGHT_ENGINE_LIGHT_CHUNK_ENTER", chunk.getPos(),
            "lit=" + lit, chunk, this);
    }

    @Inject(method = "lightChunk(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"), require = 1)
    private void phoenium$lightChunkReturned(ChunkAccess chunk, boolean lit,
                                              CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkRuntimeTrace.stageByTarget("LIGHT_ENGINE_LIGHT_CHUNK_RETURN", chunk.getPos(),
            "lit=" + lit, cir.getReturnValue(), this);
    }
}
