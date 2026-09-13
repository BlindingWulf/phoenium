package dev.phoenium.c2meprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Test-only observation target for the exact captured async-deserialize queue drain. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.ChunkIoMainThreadTaskUtils", priority = 1)
abstract class DrainProbeMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = "drainQueue", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), remap = false)
    private static void phoenium$taskFailure(@org.spongepowered.asm.mixin.injection.Coerce Object queue,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        dev.phoenium.c2meprobe.C2mePathProbe.injectDrainTask(queue);
    }
}
