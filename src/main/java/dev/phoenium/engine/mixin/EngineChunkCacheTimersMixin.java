package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineMoveSplit;
import dev.phoenium.engine.EngineTimers;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.ProfilerFiller;
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

    // S9-E10: the changed chunks' broadcast at the end of tickChunks, and the player's ChunkMap.move
    @WrapOperation(method = "tickChunks()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;broadcastChangedChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V"))
    private void phoenium$timeChunkBroadcast(ServerChunkCache cache, ProfilerFiller profiler, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(cache, profiler); } finally { EngineTimers.add(EngineTimers.CHUNK_BROADCAST, start); }
    }

    @WrapOperation(method = "move(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;move(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void phoenium$timePlayerMove(ChunkMap chunkMap, ServerPlayer player, Operation<Void> original) {
        if (EngineMoveSplit.defer(chunkMap, player)) return;   // S9-E13: a move packet's, run after the queued packets
        long start = EngineTimers.start();
        try { original.call(chunkMap, player); } finally { EngineTimers.add(EngineTimers.PLAYER_MOVE, start); }
    }
}
