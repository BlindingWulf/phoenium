package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation of POI consistency checks made during parsed chunk reads. */
@Mixin(PoiManager.class)
abstract class ChunkRuntimeTracePoiManagerMixin {
    @Inject(method = "checkConsistencyWithBlocks(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/LevelChunkSection;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$poiConsistencyStarted(SectionPos section, LevelChunkSection blocks, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("POI_CHECK_CONSISTENCY_ENTER", section.chunk(),
            "sectionY=" + section.y(), blocks, this);
    }

    @Inject(method = "checkConsistencyWithBlocks(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/LevelChunkSection;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$poiConsistencyReturned(SectionPos section, LevelChunkSection blocks, CallbackInfo ci) {
        ChunkRuntimeTrace.stageByTarget("POI_CHECK_CONSISTENCY_RETURN", section.chunk(),
            "sectionY=" + section.y(), blocks, this);
    }
}
