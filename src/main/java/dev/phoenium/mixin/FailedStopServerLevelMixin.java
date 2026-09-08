package dev.phoenium.mixin;

import dev.phoenium.chunk.FullServerShutdown;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
abstract class FailedStopServerLevelMixin {
    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;close()V"), require = 1, expect = 1)
    private void phoenium$closeLevel(Level level, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(level));
    }

    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;close()V"), require = 1, expect = 1)
    private void phoenium$closeEntities(PersistentEntitySectionManager<?> entities, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(entities));
    }
}
