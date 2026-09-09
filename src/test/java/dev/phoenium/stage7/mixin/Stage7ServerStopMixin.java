package dev.phoenium.stage7.mixin;

import dev.phoenium.stage7.Stage7ConcurrentProbe;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class Stage7ServerStopMixin {
    @Inject(method = "halt", at = @At("HEAD"))
    private void observe(boolean waitForShutdown, CallbackInfo ci) {
        Stage7ConcurrentProbe.lifetime("server-stop-request");
    }
}
