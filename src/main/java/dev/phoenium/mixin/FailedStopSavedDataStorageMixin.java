package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullServerShutdown;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SavedDataStorage.class)
abstract class FailedStopSavedDataStorageMixin {
    @Shadow private CompletableFuture<?> pendingWriteFuture;

    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/SavedDataStorage;saveAndJoin()V"), require = 1, expect = 1)
    private void phoenium$settleWrites(SavedDataStorage storage, Operation<Void> original) {
        if (!FullServerShutdown.failedStop()) {
            original.call(storage);
            return;
        }
        FullServerShutdown.cleanup(() -> pendingWriteFuture.join());
    }
}
