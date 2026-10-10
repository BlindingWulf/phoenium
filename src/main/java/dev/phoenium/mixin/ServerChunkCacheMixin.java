package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.live.ChunkRuntimeTrace;
import dev.phoenium.chunk.ChunkSynchronousAccessGuard;
import dev.phoenium.chunk.FullFinishingQueries;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.Coerce;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes one base-server chunk future per world; it never changes or completes Minecraft's future. */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"))
    private void phoenium$traceChunkFutureRequest(int x, int z, ChunkStatus status, boolean create,
                                                   CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        ChunkRuntimeTrace.futureRequested(level, x, z, status.getName());
    }

    @Inject(method = "getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"))
    private void phoenium$observeChunkFuture(int x, int z, ChunkStatus status, boolean create,
                                               CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        ChunkRuntimeTrace.futureReturned(level, x, z, status.getName(), cir.getReturnValue());
        Phoenium.liveRuntime().observeChunkFuture(level, x, z, status, cir.getReturnValue());
    }

    @Inject(method = "onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkReadyToSend(ChunkHolder holder, CallbackInfo ci) {
        ChunkRuntimeTrace.stageForHolder("SERVER_CHUNK_CACHE_ON_CHUNK_READY_TO_SEND_ENTER", holder,
            "sendSyncDone=" + holder.getSendSyncFuture().isDone(), holder);
    }

    @Inject(method = "onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$chunkReadyToSendReturn(ChunkHolder holder, CallbackInfo ci) {
        ChunkRuntimeTrace.stageForHolder("SERVER_CHUNK_CACHE_ON_CHUNK_READY_TO_SEND_RETURN", holder,
            "sendSyncDone=" + holder.getSendSyncFuture().isDone(), holder);
    }

    @WrapOperation(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"),
        require = 1, expect = 1)
    private void phoenium$rejectOwnedManagedWait(@Coerce Object loop, BooleanSupplier ready,
                                                  Operation<Void> original) {
        if (FullFinishingQueries.usesPrivateResult()) return;
        ChunkSynchronousAccessGuard.beforeWait(ready);
        original.call(loop, ready);
    }

    @WrapOperation(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;"),
        require = 2, expect = 2)
    private Object phoenium$rejectOwnedJoin(CompletableFuture<?> future, Operation<Object> original) {
        var privateResult = FullFinishingQueries.privateJoin(future);
        if (privateResult != null) return privateResult;
        ChunkSynchronousAccessGuard.beforeWait(future::isDone);
        return original.call(future);
    }

    @WrapOperation(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"),
        require = 1, expect = 1)
    private CompletableFuture<ChunkResult<ChunkAccess>> phoenium$selectedInitializedQuery(ServerChunkCache cache,
            int x, int z, ChunkStatus status, boolean create, Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
        var selected = original.call(cache, x, z, status, create);
        FullFinishingQueries.selected(level, x, z, status, create, selected);
        return selected;
    }

    // S9-D17: a condition-only wrapper (no Operation per getChunk slow path); the store runs exactly when it did.
    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;storeInCache(JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/ChunkStatus;)V"),
        require = 1, expect = 1)
    private boolean phoenium$keepInitializedResultPrivate(ServerChunkCache cache, long position, ChunkAccess chunk,
                                                          ChunkStatus status) {
        return !FullFinishingQueries.usesPrivateResult();
    }
}
