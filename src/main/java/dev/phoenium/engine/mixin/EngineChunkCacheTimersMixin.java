package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineTimers;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** S9-E8 (engine mode only): the chunk source tick's server-thread sections for {@link EngineTimers}. */
@Mixin(ServerChunkCache.class)
abstract class EngineChunkCacheTimersMixin {
    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;runDistanceManagerUpdates()Z"))
    private boolean phoenium$timeDistance(ServerChunkCache cache, Operation<Boolean> original) {
        long start = EngineTimers.start();
        try { return original.call(cache); } finally { EngineTimers.add(EngineTimers.DISTANCE, start); }
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;tickChunks()V"))
    private void phoenium$timeTickChunks(ServerChunkCache cache, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(cache); } finally { EngineTimers.add(EngineTimers.TICK_CHUNKS, start); }
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;tick()V"))
    private void phoenium$timeTracker(ChunkMap chunkMap, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(chunkMap); } finally { EngineTimers.add(EngineTimers.TRACKER, start); }
    }

    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void phoenium$timeChunkUnload(ChunkMap chunkMap, BooleanSupplier haveTime, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(chunkMap, haveTime); } finally { EngineTimers.add(EngineTimers.CHUNK_UNLOAD, start); }
    }
}
