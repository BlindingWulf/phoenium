package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pinned 26.2 Entity.placePortalTicket is a single non-blocking chunk-demand request. When a
 * same-dimension End gateway runs it under the source chunk's owner for a ticket chunk another
 * region owns, the request is retained for that owner. Every other call continues natively.
 */
@Mixin(Entity.class)
abstract class PortalTicketEntityMixin {
    @Inject(method = "placePortalTicket(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$ownerCorrectPortalTicket(BlockPos position, CallbackInfo ci) {
        if (Phoenium.liveRuntime().deferForeignPortalTicket((Entity) (Object) this, position)) ci.cancel();
    }
}
