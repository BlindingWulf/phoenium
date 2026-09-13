package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
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

/** Binds C2ME's fully asynchronous DISK parse to the same exact Phoenium reload transaction. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDiskAsync", remap = false)
abstract class C2meReadFromDiskAsyncMixin {
    private static final String READ = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;read("
            + "Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
            + "Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;"
            + "Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk;";
    private static final String MAP = "Lio/reactivex/rxjava3/core/Single;map("
            + "Lio/reactivex/rxjava3/functions/Function;)Lio/reactivex/rxjava3/core/Single;";
    private static final String FINALIZE = "Lcom/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDiskAsync;finalizeLoading("
            + "Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;"
            + "Lio/reactivex/rxjava3/core/Single;)Lio/reactivex/rxjava3/core/Completable;";

    @WrapOperation(method = "lambda$invokeAsyncLoad$0",
            at = @At(value = "INVOKE", target = READ, remap = true), require = 1, expect = 1)
    private ProtoChunk phoenium$asyncDiskRead(SerializableChunkData data, ServerLevel level, PoiManager poi,
                                              RegionStorageInfo storage, ChunkPos position, Operation<ProtoChunk> original,
                                              @Coerce Object context) {
        return Phoenium.liveRuntime().chunkBinding().c2meReadAsync(context, data, level, position,
                () -> original.call(data, level, poi, storage, position));
    }

    /**
     * The locked provider has exactly two Single.map calls in invokeAsyncLoad; ordinal 1 is the
     * sole final map after observeOn(mainThreadExecutor).  Rebinding that node to a flatMap lets
     * Phoenium route C2ME's original final mapper through regional ownership without blocking the
     * main event loop.
     */
    @WrapOperation(method = "invokeAsyncLoad",
            at = @At(value = "INVOKE", target = MAP, ordinal = 1, remap = false), require = 1, expect = 1)
    @Coerce
    private Object phoenium$ownerRoutedFinalMap(@Coerce Object source, @Coerce Object providerMapper,
                                                Operation<Object> original, @Coerce Object context) {
        return Phoenium.liveRuntime().chunkBinding().c2meAsyncFinalMap(context, source, providerMapper);
    }

    /** Bind cancellation at the outer retry/fallback lifetime, not an individual retry Single. */
    @WrapOperation(method = "upgradeToThis", at = @At(value = "INVOKE", target = FINALIZE, remap = false),
            require = 1, expect = 1)
    @Coerce
    private Object phoenium$outerCancellation(@Coerce Object instance, @Coerce Object context,
                                               @Coerce Object source, Operation<Object> original) {
        Object completable = original.call(instance, context, source);
        return Phoenium.liveRuntime().chunkBinding().c2meAsyncOuterLifecycle(context, completable);
    }
}
