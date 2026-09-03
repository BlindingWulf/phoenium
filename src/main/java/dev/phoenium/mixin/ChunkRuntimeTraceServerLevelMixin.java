package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation of the server-level ticking and unload boundaries. */
@Mixin(ServerLevel.class)
abstract class ChunkRuntimeTraceServerLevelMixin {
    @Inject(method = "startTickingChunk(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$startTickingEnter(LevelChunk chunk, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        ChunkRuntimeTrace.stage("SERVER_LEVEL_START_TICKING_CHUNK_ENTER", level, chunk.getPos(),
            "fullStatus=" + chunk.getFullStatus(), chunk, null);
    }

    @Inject(method = "startTickingChunk(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$startTickingReturn(LevelChunk chunk, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        ChunkRuntimeTrace.stage("SERVER_LEVEL_START_TICKING_CHUNK_RETURN", level, chunk.getPos(),
            "fullStatus=" + chunk.getFullStatus(), chunk, null);
    }

    @Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$unloadEnter(LevelChunk chunk, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        ChunkRuntimeTrace.stage("SERVER_LEVEL_UNLOAD_CHUNK_ENTER", level, chunk.getPos(),
            "fullStatus=" + chunk.getFullStatus(), chunk, null);
    }

    @Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$unloadReturn(LevelChunk chunk, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        ChunkRuntimeTrace.stage("SERVER_LEVEL_UNLOAD_CHUNK_RETURN", level, chunk.getPos(),
            "fullStatus=" + chunk.getFullStatus(), chunk, null);
    }
}
