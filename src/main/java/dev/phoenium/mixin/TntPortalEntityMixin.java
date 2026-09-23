package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stop before destination lookup and unwind the native source owner callback. */
@Mixin(Entity.class)
abstract class TntPortalEntityMixin {
    @Inject(method = "handlePortal()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$retainTntDestination(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof PrimedTnt tnt
                && Phoenium.liveRuntime().suspendTntPortalDestination(tnt, self.portalProcess)) {
            Profiler.get().pop();
            ci.cancel();
        }
    }
}
