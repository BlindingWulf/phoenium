package dev.phoenium.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class ItemPortalEntityMixin {
    @Inject(method = "handlePortal()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;"),
        cancellable = true, require = 1, allow = 1)
    private void phoenium$retainItemPortal(CallbackInfo ci, @Local TeleportTransition transition) {
        if ((Object) this instanceof ItemEntity item && Phoenium.liveRuntime().captureItemPortal(item, transition)) {
            Profiler.get().pop(); ci.cancel();
        }
    }
    @Inject(method = "baseTick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;handlePortal()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void phoenium$returnItemBaseTick(CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity item && Phoenium.liveRuntime().itemPortalSuspended(item)) {
            Profiler.get().pop(); ci.cancel();
        }
    }
}
