package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.live.NativePortalTransfer;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The native method and its real return stay intact. Only a retained owner-free portal
 * invocation installs NativePortalTransfer; ordinary/same-dimension calls are unchanged. */
@Mixin(Entity.class)
abstract class NativePortalTransferMixin {
    @WrapOperation(method = "teleport", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;stopRiding()V"), require = 1, allow = 1)
    private void phoenium$portalStopRiding(Entity source, Operation<Void> original) {
        NativePortalTransfer.sourceEffect(source, "STOP_RIDING", () -> original.call(source));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;ejectPassengers()V"), require = 1, allow = 1)
    private void phoenium$portalEject(Entity source, Operation<Void> original) {
        NativePortalTransfer.sourceEffect(source, "EJECT", () -> original.call(source));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getPassengers()Ljava/util/List;"), require = 1, allow = 1)
    private java.util.List<Entity> phoenium$portalPassengers(Entity source, Operation<java.util.List<Entity>> original) {
        return NativePortalTransfer.sourceRead(source, "CAPTURE_PASSENGERS",
            () -> original.call(source));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;calculatePassengerTransition(Lnet/minecraft/world/level/portal/TeleportTransition;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"), require = 1, allow = 1)
    private TeleportTransition phoenium$portalPassengerTransition(Entity parent, TeleportTransition transition,
            Entity passenger, Operation<TeleportTransition> original) {
        return NativePortalTransfer.sourceRead(parent, "PASSENGER_TRANSITION",
            () -> original.call(parent, transition, passenger));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;"), require = 1, allow = 1)
    private Entity phoenium$portalPassenger(Entity passenger, TeleportTransition transition, Operation<Entity> original) {
        return NativePortalTransfer.passenger(passenger, transition, () -> original.call(passenger, transition));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;"), require = 1, allow = 1)
    private Entity phoenium$portalCreate(EntityType<?> type, Level level, EntitySpawnReason reason, Operation<Entity> original) {
        return NativePortalTransfer.create((Entity) (Object) this, () -> original.call(type, level, reason));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;restoreFrom(Lnet/minecraft/world/entity/Entity;)V"), require = 1, allow = 1)
    private void phoenium$portalRestore(Entity replacement, Entity source, Operation<Void> original) {
        NativePortalTransfer.restore(replacement, source, () -> original.call(replacement, source));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;removeAfterChangingDimensions()V"), require = 1, allow = 1)
    private void phoenium$portalRemove(Entity source, Operation<Void> original) {
        NativePortalTransfer.removeSource(source, () -> original.call(source));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/PositionMoveRotation;of(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/entity/PositionMoveRotation;"), require = 1, allow = 1)
    private PositionMoveRotation phoenium$portalRetiredSourcePosition(Entity source,
            Operation<PositionMoveRotation> original) {
        return NativePortalTransfer.sourcePosition(source, () -> original.call(source));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;teleportSetPosition(Lnet/minecraft/world/entity/PositionMoveRotation;Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V"), require = 1, allow = 1)
    private void phoenium$portalPosition(Entity entity, PositionMoveRotation from, PositionMoveRotation to, Set<Relative> relatives, Operation<Void> original) {
        NativePortalTransfer.destinationEffect(entity, "POSITION", () -> original.call(entity, from, to, relatives));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addDuringTeleport(Lnet/minecraft/world/entity/Entity;)V"), require = 1, allow = 1)
    private void phoenium$portalInsert(ServerLevel level, Entity entity, Operation<Void> original) {
        NativePortalTransfer.insert(level, entity, () -> original.call(level, entity));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z"), require = 1, allow = 1)
    private boolean phoenium$portalReattach(Entity passenger, Entity vehicle, boolean force, boolean suppressCancellation,
            Operation<Boolean> original) {
        return NativePortalTransfer.attachPassenger(passenger, vehicle,
            () -> original.call(passenger, vehicle, force, suppressCancellation));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;resetEmptyTime()V"), require = 1, allow = 1)
    private void phoenium$portalReset(ServerLevel level, Operation<Void> original) {
        NativePortalTransfer.destinationWorldEffect(level, () -> original.call(level));
    }
    @WrapOperation(method = "teleportCrossDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/portal/TeleportTransition$PostTeleportTransition;onTransition(Lnet/minecraft/world/entity/Entity;)V"), require = 1, allow = 1)
    private void phoenium$portalPost(TeleportTransition.PostTeleportTransition callback, Entity entity, Operation<Void> original) {
        NativePortalTransfer.destinationEffect(entity, "POST_TRANSITION", () -> original.call(callback, entity));
    }
}
