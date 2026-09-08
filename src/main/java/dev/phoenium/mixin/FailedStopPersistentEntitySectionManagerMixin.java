package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullServerShutdown;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PersistentEntitySectionManager.class)
abstract class FailedStopPersistentEntitySectionManagerMixin {
    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;saveAll()V"), require = 1, expect = 1)
    private void phoenium$saveAll(PersistentEntitySectionManager<?> manager, Operation<Void> original) {
        if (!FullServerShutdown.failedStop()) original.call(manager);
    }

    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityPersistentStorage;close()V"), require = 1, expect = 1)
    private void phoenium$closeStorage(EntityPersistentStorage<?> storage, Operation<Void> original) {
        FailedStopCleanup.run(() -> original.call(storage));
    }
}
