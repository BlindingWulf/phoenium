package dev.phoenium.adversestop.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.adversestop.AdverseStopProbe;
import dev.phoenium.chunk.FullServerShutdown;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = FullServerShutdown.class, remap = false)
abstract class AdverseFullServerShutdownMixin {
    @WrapMethod(method = "run(Lnet/minecraft/server/MinecraftServer;Ljava/lang/Runnable;Ljava/lang/Runnable;Ljava/lang/Runnable;Ljava/lang/Runnable;)V")
    private static void phoenium$observeExactFailure(MinecraftServer server, Runnable closeAdmission,
            Runnable drainAdmitted, Runnable original, Runnable acknowledgement, Operation<Void> operation) {
        try { operation.call(server, closeAdmission, drainAdmitted, original, acknowledgement); }
        catch (Throwable failure) { AdverseStopProbe.shutdownFailure(server, failure); throw failure; }
    }
}
