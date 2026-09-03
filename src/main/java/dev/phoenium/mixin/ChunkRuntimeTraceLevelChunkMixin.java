package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation of the exact full-chunk post-processing and loaded-bit boundaries. */
@Mixin(LevelChunk.class)
abstract class ChunkRuntimeTraceLevelChunkMixin {
    @Inject(method = "postProcessGeneration(Lnet/minecraft/server/level/ServerLevel;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$postProcessEnter(ServerLevel level, CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        ChunkRuntimeTrace.stage("LEVEL_CHUNK_POST_PROCESS_GENERATION_ENTER", level, chunk.getPos(),
            null, chunk, null);
    }

    @Inject(method = "postProcessGeneration(Lnet/minecraft/server/level/ServerLevel;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$postProcessReturn(ServerLevel level, CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        ChunkRuntimeTrace.stage("LEVEL_CHUNK_POST_PROCESS_GENERATION_RETURN", level, chunk.getPos(),
            null, chunk, null);
    }

    @Inject(method = "runPostLoad()V", at = @At("HEAD"), require = 1)
    private void phoenium$postLoadEnter(CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        stageWithoutLevel("LEVEL_CHUNK_RUN_POST_LOAD_ENTER", chunk);
    }

    @Inject(method = "runPostLoad()V", at = @At("RETURN"), require = 1)
    private void phoenium$postLoadReturn(CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        stageWithoutLevel("LEVEL_CHUNK_RUN_POST_LOAD_RETURN", chunk);
    }

    @Inject(method = "setLoaded(Z)V", at = @At("HEAD"), require = 1)
    private void phoenium$setLoadedEnter(boolean loaded, CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        stageWithoutLevel("LEVEL_CHUNK_SET_LOADED_ENTER", chunk, "loaded=" + loaded);
    }

    @Inject(method = "setLoaded(Z)V", at = @At("RETURN"), require = 1)
    private void phoenium$setLoadedReturn(boolean loaded, CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        stageWithoutLevel("LEVEL_CHUNK_SET_LOADED_RETURN", chunk, "loaded=" + loaded);
    }

    private static void stageWithoutLevel(String stage, LevelChunk chunk, String... details) {
        Level level = chunk.getLevel();
        String detail = details.length == 0 ? null : details[0];
        if (level instanceof ServerLevel serverLevel) {
            ChunkRuntimeTrace.stage(stage, serverLevel, chunk.getPos(), detail, chunk, null);
        } else {
            ChunkRuntimeTrace.stageByTarget(stage, chunk.getPos(), detail, chunk, null);
        }
    }
}
