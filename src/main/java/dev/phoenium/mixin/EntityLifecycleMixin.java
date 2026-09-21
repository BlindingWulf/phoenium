package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullEntityFacts;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publish committed routing/coordinates before original movement observers can use
 * them or fail. No world entity-index lookup is performed at this source edge. */
@Mixin(Entity.class)
abstract class EntityLifecycleMixin {
    @Inject(method = "setPosRaw(DDD)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onMove()V"), require = 1)
    private void phoenium$routingObserved(double x, double y, double z, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        FullEntityFacts.routed(entity);
        Phoenium.liveRuntime().onEntityRoutingObserved(entity);
    }

    /** Serialize the native level edge with movement admission and publish both transition cuts. */
    @WrapMethod(method = "setLevel(Lnet/minecraft/world/level/Level;)V")
    private void phoenium$playerLevelChange(Level level, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof ServerPlayer player
                && level instanceof net.minecraft.server.level.ServerLevel destination
                && player.level() != destination) {
            Phoenium.liveRuntime().onPlayerLevelChanging(player, destination);
            try {
                original.call(level);
            } finally {
                Phoenium.liveRuntime().onPlayerLevelChanged(player);
            }
        } else {
            original.call(level);
        }
    }

    @Inject(method = "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", at = @At("HEAD"), require = 1)
    private void phoenium$removed(Entity.RemovalReason reason, CallbackInfo ci) {
        FullEntityFacts.passengerRemoved((Entity) (Object) this);
        Phoenium.liveRuntime().onEntityRemoved((Entity) (Object) this, reason);
    }

    @Inject(method = {"addPassenger", "removePassenger"}, at = @At("TAIL"), require = 2)
    private void phoenium$passengerRelation(Entity passenger, CallbackInfo ci) {
        FullEntityFacts.passengersChanged((Entity) (Object) this);
    }
}
