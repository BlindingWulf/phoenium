package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suspend before EndPlatformFeature and unwind the native source callback. */
@Mixin(Entity.class)
abstract class FallingBlockPortalEntityMixin {
    @Inject(method="handlePortal()V", at=@At(value="INVOKE", target=
        "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable=true, require=1, allow=1)
    private void phoenium$retainFallingBlockDestination(CallbackInfo ci) {
        if ((Object)this instanceof FallingBlockEntity block
                && Phoenium.liveRuntime().suspendFallingBlockPortalDestination(block, block.portalProcess)) {
            Profiler.get().pop(); ci.cancel();
        }
    }
}
