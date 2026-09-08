package dev.phoenium.adversestop.mixin;

import dev.phoenium.adversestop.AdverseStopProbe;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class AdverseMinecraftServerMixin {
    @Inject(method = "stopServer()V", at = @At("RETURN"), require = 1)
    private void phoenium$nativeStopReturned(CallbackInfo ci) {
        AdverseStopProbe.nativeStopReturned((MinecraftServer) (Object) this);
    }
}
