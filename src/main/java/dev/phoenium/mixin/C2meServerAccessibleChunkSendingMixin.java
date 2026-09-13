package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Rebinds the effective notickvd SEND_READY graph through its untouched generic bridge. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessibleChunkSending",
        remap = false, priority = 900)
abstract class C2meServerAccessibleChunkSendingMixin {
    private static final String UPGRADE_BRIDGE = "upgradeToThis("
            + "Ljava/lang/Object;"
            + "Lcom/ishland/flowsched/scheduler/Cancellable;)Lio/reactivex/rxjava3/core/Completable;";
    private static final String TYPED_UPGRADE = "Lcom/ishland/c2me/rewrites/chunksystem/common/statuses/"
            + "ServerAccessibleChunkSending;upgradeToThis("
            + "Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;"
            + "Lcom/ishland/flowsched/scheduler/Cancellable;)Lio/reactivex/rxjava3/core/Completable;";

    @WrapOperation(method = UPGRADE_BRIDGE,
            at = @At(value = "INVOKE", target = TYPED_UPGRADE, remap = false), require = 1, expect = 1)
    @Coerce
    private Object phoenium$sendReady(@Coerce Object receiver, @Coerce Object context, @Coerce Object cancellable,
                                      Operation<Object> original) {
        Object providerGraph = original.call(receiver, context, cancellable);
        return Phoenium.liveRuntime().chunkBinding().c2meSendReadyGraph(context, cancellable, providerGraph);
    }
}
