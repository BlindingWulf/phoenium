package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.C2meDiskReadRx;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Binds C2ME's synchronous fallback DISK parse to the exact Phoenium reload transaction. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDisk", remap = false)
abstract class C2meReadFromDiskMixin {
    @Inject(method = "upgradeToThis(Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;Lcom/ishland/flowsched/scheduler/Cancellable;)Lio/reactivex/rxjava3/core/Completable;",
            at = @At("HEAD"), require = 1)
    private void phoenium$bindAction(@Coerce Object context, @Coerce Object token, CallbackInfoReturnable<Object> callback) {
        Phoenium.liveRuntime().chunkBinding().c2meReadAction(context, token);
    }

    private static final String READ = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;read("
            + "Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
            + "Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;"
            + "Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk;";

    @WrapOperation(method = "lambda$invokeVanillaLoad$0",
            at = @At(value = "INVOKE", target = READ, remap = true), require = 1, expect = 1)
    private ProtoChunk phoenium$diskRead(SerializableChunkData data, ServerLevel level, PoiManager poi,
                                         RegionStorageInfo storage, ChunkPos position, Operation<ProtoChunk> original,
                                         @Coerce Object context) {
        return Phoenium.liveRuntime().chunkBinding().c2meRead(context, data, level, position,
                () -> original.call(data, level, poi, storage, position));
    }
    @Inject(method = "lambda$finalizeLoading$3", at = @At("HEAD"), require = 1)
    private static void phoenium$publicationFence(@Coerce Object context, ProtoChunk chunk, CallbackInfo callback) {
        Phoenium.liveRuntime().chunkBinding().c2meReadCommit(context);
    }

    @WrapOperation(method = "finalizeLoading",
            at = @At(value = "INVOKE", target = "Lio/reactivex/rxjava3/core/Single;onErrorResumeNext(Lio/reactivex/rxjava3/functions/Function;)Lio/reactivex/rxjava3/core/Single;"), require = 1)
    @Coerce
    private Object phoenium$guardRecovery(@Coerce Object source, @Coerce Object function, Operation<Object> original,
                                         @Coerce Object context) {
        return original.call(source, C2meDiskReadRx.guardRecovery(function,
                Phoenium.liveRuntime().chunkBinding().c2meReadOperation(context)));
    }

    @WrapOperation(method = "finalizeLoading",
            at = @At(value = "INVOKE", target = "Lio/reactivex/rxjava3/core/Single;doOnError(Lio/reactivex/rxjava3/functions/Consumer;)Lio/reactivex/rxjava3/core/Single;"), require = 1)
    @Coerce
    private Object phoenium$guardFailureReport(@Coerce Object source, @Coerce Object consumer, Operation<Object> original,
                                              @Coerce Object context) {
        return original.call(source, C2meDiskReadRx.guardFailureReport(consumer,
                Phoenium.liveRuntime().chunkBinding().c2meReadOperation(context)));
    }

    @WrapOperation(method = "upgradeToThis",
            at = @At(value = "INVOKE", target = "Lcom/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDisk;finalizeLoading(Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;Lio/reactivex/rxjava3/core/Single;)Lio/reactivex/rxjava3/core/Completable;"), require = 1)
    @Coerce
    private Object phoenium$syncTerminal(@Coerce Object instance, @Coerce Object context, @Coerce Object source,
                                         Operation<Object> original) {
        return Phoenium.liveRuntime().chunkBinding().c2meAsyncOuterLifecycle(context, original.call(instance, context, source));
    }

    @WrapOperation(method = "upgradeToThis",
            at = @At(value = "INVOKE", target = "Lio/reactivex/rxjava3/core/Single;retryWhen(Lio/reactivex/rxjava3/functions/Function;)Lio/reactivex/rxjava3/core/Single;"), require = 1)
    @Coerce
    private Object phoenium$guardSyncRetry(@Coerce Object source, @Coerce Object function, Operation<Object> original,
                                          @Coerce Object context) {
        return original.call(source, C2meDiskReadRx.guardRetry(function,
                Phoenium.liveRuntime().chunkBinding().c2meReadOperation(context)));
    }

}
