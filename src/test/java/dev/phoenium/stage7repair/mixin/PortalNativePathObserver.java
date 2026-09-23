package dev.phoenium.stage7repair.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.stage7repair.PortalLifecycleObserverBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Read-only test observer of the exact native portal call and cross-dimension entry. */
@Mixin(Entity.class)
abstract class PortalNativePathObserver {
    @WrapOperation(method = "handlePortal()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;"),
        require = 1, allow = 1)
    private Entity stage7$nativePortalInvocation(Entity source, TeleportTransition transition,
            Operation<Entity> original) {
        return PortalLifecycleObserverBridge.nativePortalInvocation(source, transition,
            () -> original.call(source, transition));
    }

    @Inject(method = "teleportCrossDimension", at = @At("HEAD"), require = 1)
    private void stage7$crossDimensionHead(ServerLevel oldLevel, ServerLevel newLevel,
            TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
        PortalLifecycleObserverBridge.crossDimensionHead((Entity) (Object) this, oldLevel, newLevel);
    }
}
