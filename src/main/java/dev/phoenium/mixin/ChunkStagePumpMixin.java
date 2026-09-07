package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Delivers one typed chunk stage only at an owner-free Minecraft event-loop poll point. */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
abstract class ChunkStagePumpMixin {
    @Inject(method = "pollTask()Z", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
    private void phoenium$pump(CallbackInfoReturnable<Boolean> cir) {
        if (Phoenium.liveRuntime().pumpFullOne() || Phoenium.liveRuntime().chunkBinding().pumpOne()) cir.setReturnValue(true);
    }
}
