package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** S9-D19: the owner work admitted by this frame's packets runs after they are handled and before tickServer, where
 * vanilla runs those handlers (processPacketsAndTick handles the queued packets, then ticks). */
@Mixin(MinecraftServer.class)
abstract class PacketWorkDrainMixin {
    @Inject(method = "processPacketsAndTick(Z)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V"),
        require = 1, allow = 1)
    private void phoenium$drainPacketWork(boolean sprinting, CallbackInfo ci) {
        Phoenium.liveRuntime().drainAdmittedPacketWork((MinecraftServer) (Object) this);
    }
}
