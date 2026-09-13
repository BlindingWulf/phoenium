package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Delivers one typed chunk stage only at an owner-free Minecraft event-loop poll point. */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
abstract class ChunkStagePumpMixin {
    @Shadow @Final private ServerChunkCache this$0;

    /** Native chunk post-processing continuations also run between server ticks. Reserve
     * their named services at this original executor edge, before any regional producer
     * admission. Reentrant tasks retain the same Runnable in the same native queue. */
    @WrapMethod(method = "doRunTask(Ljava/lang/Runnable;)V")
    private void phoenium$chunkTaskServices(Runnable task, Operation<Void> original) {
        var server = ((RegionalServerChunkCacheAccess) this$0).phoenium$getLevel().getServer();
        var executor = (BlockableEventLoop<Runnable>) (Object) this;
        FullFinishingServices.runServerTask(server, () -> executor.schedule(task), () -> original.call(task));
    }

    @Inject(method = "pollTask()Z", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
    private void phoenium$pump(CallbackInfoReturnable<Boolean> cir) {
        if (Phoenium.liveRuntime().pumpSaveOne() || Phoenium.liveRuntime().pumpFullOne()
                || Phoenium.liveRuntime().chunkBinding().pumpOne()
                || Phoenium.liveRuntime().pumpC2mePersistedReadOne()) cir.setReturnValue(true);
    }
}
