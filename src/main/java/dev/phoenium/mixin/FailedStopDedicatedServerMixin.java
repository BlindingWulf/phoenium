package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** DedicatedServer's executor shutdown follows super.stopServer; an acknowledged
 * failure from that call must not skip native executor termination. */
@Mixin(DedicatedServer.class)
abstract class FailedStopDedicatedServerMixin {
    @WrapOperation(method = "stopServer()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;stopServer()V"))
    private void phoenium$finishDedicatedFailure(DedicatedServer server, Operation<Void> original) {
        server.halt(false);
        try { original.call(server); }
        catch (Throwable failure) {
            try { net.minecraft.util.Util.shutdownExecutors(); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
