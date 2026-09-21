package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Retains C2ME's exact Rx scheduler while replacing only its provider Runnable with an owner-admitted wrapper. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessible", remap = false)
abstract class C2meServerAccessibleMixin {
    /** Observe C2ME's real removal, including chunks with no active consumer.
     * The returned provider holder is the only retirement identity; no provider mutation is added. */
    @WrapOperation(method = "lambda$downgradeFromThis$0",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;remove(J)Ljava/lang/Object;", remap = false),
            require = 1, expect = 1)
    private Object phoenium$residentRemoved(it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<?> holders,
                                           long key, Operation<Object> original) {
        Object removed = original.call(holders, key);
        if (removed instanceof net.minecraft.server.level.GenerationChunkHolder holder)
            dev.phoenium.chunk.ChunkHolderLifetime.residentSourceRetired(holder);
        return removed;
    }

    private static final String UPGRADE = "upgradeToThis("
            + "Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;"
            + "Lcom/ishland/flowsched/scheduler/Cancellable;)Lio/reactivex/rxjava3/core/Completable;";
    private static final String POST = "postUpgradeToThis("
            + "Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;)Lio/reactivex/rxjava3/core/Completable;";
    private static final String FROM_RUNNABLE = "Lio/reactivex/rxjava3/core/Completable;fromRunnable("
            + "Ljava/lang/Runnable;)Lio/reactivex/rxjava3/core/Completable;";

    @WrapOperation(method = UPGRADE,
            at = @At(value = "INVOKE", target = FROM_RUNNABLE, remap = false),
            require = 2, expect = 2)
    @Coerce
    private Object phoenium$full(Runnable providerBody, Operation<Object> original,
                                 @Coerce Object context, @Coerce Object cancellable) {
        return original.call(Phoenium.liveRuntime().chunkBinding().c2meFullRunnable(context, providerBody));
    }

    @WrapOperation(method = POST,
            at = @At(value = "INVOKE", target = FROM_RUNNABLE, remap = false),
            require = 1, expect = 1)
    @Coerce
    private Object phoenium$delayedFullEvents(Runnable providerBody, Operation<Object> original,
                                              @Coerce Object context) {
        return original.call(Phoenium.liveRuntime().chunkBinding().c2meDelayedFullEventsRunnable(context, providerBody));
    }
}
