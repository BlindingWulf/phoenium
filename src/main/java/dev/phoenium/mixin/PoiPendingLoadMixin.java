package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Prevents the background iterator from racing a Q currently assigned to owner-admitted install. */
@Mixin(SectionStorage.class)
abstract class PoiPendingLoadMixin {
    @WrapOperation(method = "unpackPendingLoads()V",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;getNow(Ljava/lang/Object;)Ljava/lang/Object;"),
        require = 1, expect = 1)
    private Object phoenium$ownerInstall(CompletableFuture<?> future, Object absent, Operation<Object> original) {
        return Phoenium.liveRuntime().chunkBinding().ownsPoiFuture(future) ? null : original.call(future, absent);
    }
}
