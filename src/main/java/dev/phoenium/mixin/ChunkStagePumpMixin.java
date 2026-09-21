package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.service.ManagedBlockPollTurnFence;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Delivers one typed chunk stage only at an owner-free Minecraft event-loop poll point. */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
abstract class ChunkStagePumpMixin implements ManagedBlockPollTurnFence.Loop {
    @Shadow @Final private ServerChunkCache this$0;
    @Unique private ManagedBlockPollTurnFence phoenium$spawnFence;

    @Override public ManagedBlockPollTurnFence phoenium$naturalSpawnFence() {
        if (phoenium$spawnFence == null)
            phoenium$spawnFence = new ManagedBlockPollTurnFence(Thread.currentThread());
        return phoenium$spawnFence;
    }

    @WrapMethod(method = "pollTask()Z")
    private boolean phoenium$nativePollReturn(Operation<Boolean> original) {
        return phoenium$spawnFence == null ? original.call()
            : phoenium$spawnFence.poll(() -> original.call());
    }

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
