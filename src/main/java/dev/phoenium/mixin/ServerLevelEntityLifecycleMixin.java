package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.server.level.ServerLevel;
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
abstract class ServerLevelEntityLifecycleMixin implements dev.phoenium.service.RegionalEntityStorageWorld {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private ServerLevel this$0;

    @Override public ServerLevel phoenium$entityStorageWorld() { return this$0; }
    /** Distance-manager promotion/demotion also reaches these callbacks from pollTask, outside
     * tickServer. Reserve the existing named lifecycle turn before tracker regional admission;
     * an enclosing serial or exact FULL operation is retained unchanged. */
    @WrapMethod(method = {"onTrackingStart(Lnet/minecraft/world/entity/Entity;)V",
        "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V"})
    private void phoenium$trackingLifecycle(Entity entity, Operation<Void> original) {
        ServerLevel level = (ServerLevel) entity.level();
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) {
            original.call(entity);
        }
    }

    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"),
        require = 1, expect = 1)
    private void phoenium$trackingStarted(Entity entity, CallbackInfo ci) {
        Phoenium.liveRuntime().onEntityAddressable(entity);
    }
}
