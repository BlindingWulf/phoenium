package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Scope of one native entity tick for EntityPortalTransitionManager's classes; all others run unchanged. */
@Mixin(ServerLevel.class)
abstract class EntityPortalTickMixin {
    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"),
        require = 1, allow = 1)
    private void phoenium$entityPortalTick(Entity entity, Operation<Void> original) {
        Phoenium.liveRuntime().entityPortalTick(entity, () -> original.call(entity));
    }
}
