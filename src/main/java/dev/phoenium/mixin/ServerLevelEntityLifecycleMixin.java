package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes regional object ownership at vanilla's common visible-entity lifecycle edge.
 * Persistent chunk loads and world-generation insertion enter the entity manager directly and do
 * not call ServerLevel.addFreshEntity/addWithUUID. EntityLookup.add has already installed the
 * owner-published identity and route before this callback; the original tracker body has not run.
 */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
abstract class ServerLevelEntityLifecycleMixin {
    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"),
        require = 1, expect = 1)
    private void phoenium$trackingStarted(Entity entity, CallbackInfo ci) {
        Phoenium.liveRuntime().onEntityAddressable(entity);
    }
}
