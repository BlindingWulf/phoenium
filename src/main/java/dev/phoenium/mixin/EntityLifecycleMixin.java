package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullEntityFacts;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Derived target-index lifecycle only; no world entity-index lookup is performed here. */
@Mixin(Entity.class)
abstract class EntityLifecycleMixin {
    @Inject(method = "setPosRaw(DDD)V", at = @At("TAIL"), require = 1)
    private void phoenium$routingObserved(double x, double y, double z, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        FullEntityFacts.routed(entity);
        Phoenium.liveRuntime().onEntityRoutingObserved(entity);
    }

    @Inject(method = "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", at = @At("HEAD"), require = 1)
    private void phoenium$removed(Entity.RemovalReason reason, CallbackInfo ci) {
        Phoenium.liveRuntime().onEntityRemoved((Entity) (Object) this);
    }
}
