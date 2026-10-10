package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.ChunkSynchronousAccessGuard;
import dev.phoenium.chunk.FullFinishingQueries;
import dev.phoenium.live.ChunkRuntimeTrace;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The locked Lithium release moves vanilla blocking and future selection into
 * ServerChunkCache#getChunkBlocking. Keep Phoenium's guards at those exact calls.
 */
@Mixin(value = ServerChunkCache.class, priority = 1100)
abstract class LithiumServerChunkCacheMixin {
    private static final String GET_CHUNK =
            "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;";
    private static final String GET_CHUNK_BLOCKING =
            "getChunkBlocking(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;";
    @Shadow @Final private ServerLevel level;

    @org.spongepowered.asm.mixin.injection.Inject(method = "getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"))
    private void phoenium$traceChunkFutureRequest(int x, int z, ChunkStatus status, boolean create,
                                                   CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        ChunkRuntimeTrace.futureRequested(level, x, z, status.getName());
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"))
    private void phoenium$observeChunkFuture(int x, int z, ChunkStatus status, boolean create,
                                               CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        ChunkRuntimeTrace.futureReturned(level, x, z, status.getName(), cir.getReturnValue());
        Phoenium.liveRuntime().observeChunkFuture(level, x, z, status, cir.getReturnValue());
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkReadyToSend(ChunkHolder holder, CallbackInfo ci) {
        ChunkRuntimeTrace.stageForHolder("SERVER_CHUNK_CACHE_ON_CHUNK_READY_TO_SEND_ENTER", holder,
            "sendSyncDone=" + holder.getSendSyncFuture().isDone(), holder);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$chunkReadyToSendReturn(ChunkHolder holder, CallbackInfo ci) {
        ChunkRuntimeTrace.stageForHolder("SERVER_CHUNK_CACHE_ON_CHUNK_READY_TO_SEND_RETURN", holder,
            "sendSyncDone=" + holder.getSendSyncFuture().isDone(), holder);
    }

    @WrapOperation(method = GET_CHUNK_BLOCKING,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"),
        require = 1, expect = 1)
    private void phoenium$rejectOwnedManagedWait(@Coerce Object loop, BooleanSupplier ready,
                                                  Operation<Void> original) {
        if (FullFinishingQueries.usesPrivateResult()) return;
        ChunkSynchronousAccessGuard.beforeWait(ready);
        original.call(loop, ready);
    }

    @WrapOperation(method = GET_CHUNK_BLOCKING,
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;"),
        require = 2, expect = 2)
    private Object phoenium$rejectOwnedJoin(CompletableFuture<?> future, Operation<Object> original) {
        var privateResult = FullFinishingQueries.privateJoin(future);
        if (privateResult != null) return privateResult;
        ChunkSynchronousAccessGuard.beforeWait(future::isDone);
        return original.call(future);
    }

    @WrapOperation(method = GET_CHUNK_BLOCKING,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;scheduleChunkGenerationTask(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/server/level/ChunkMap;)Ljava/util/concurrent/CompletableFuture;"),
        require = 1, expect = 1)
    private CompletableFuture<ChunkResult<ChunkAccess>> phoenium$selectedInitializedQuery(ChunkHolder holder,
            ChunkStatus status, ChunkMap map, Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original,
            @Local(argsOnly = true) boolean create) {
        var selected = original.call(holder, status, map);
        var position = holder.getPos();
        FullFinishingQueries.selected(level, position.x(), position.z(), status, create, selected);
        return selected;
    }

    // S9-D17: a condition-only wrapper (no Operation per getChunk slow path); the store runs exactly when it did.
    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = GET_CHUNK,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;addToCache(JLnet/minecraft/world/level/chunk/ChunkAccess;)V"),
        require = 1, expect = 1)
    private boolean phoenium$keepInitializedResultPrivate(ServerChunkCache cache, long position, ChunkAccess chunk) {
        return !FullFinishingQueries.usesPrivateResult();
    }
}
