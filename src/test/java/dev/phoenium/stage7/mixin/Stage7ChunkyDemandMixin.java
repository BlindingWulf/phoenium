package dev.phoenium.stage7.mixin;

import dev.phoenium.stage7.Stage7ConcurrentProbe;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import net.minecraft.server.level.ServerLevel;

/** Exact audited 1.5.3 adapter edge; return the identical upstream future. */
@Pseudo
@Mixin(targets = "org.popcraft.chunky.platform.FabricWorld", remap = false)
abstract class Stage7ChunkyDemandMixin {
    @Shadow @Final private ServerLevel world;
    @WrapMethod(method = "getChunkAtAsync")
    private CompletableFuture<Void> observe(int x, int z, Operation<CompletableFuture<Void>> original) {
        // Upstream re-enters this method on the server thread. Observe the external
        // producer invocation once, including its native enqueue-to-completion interval.
        var request = world.getServer().isSameThread() ? null : Stage7ConcurrentProbe.begin(x, z);
        try {
            var returned = original.call(x, z);
            if (request != null) returned.whenComplete((value, failure) -> request.ended(failure));
            return returned;
        } catch (RuntimeException | Error failure) {
            if (request != null) request.ended(failure);
            throw failure;
        }
    }
}
