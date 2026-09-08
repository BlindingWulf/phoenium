package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
abstract class Stage4ServerEntityMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private Entity entity;
    @org.spongepowered.asm.mixin.Shadow private int tickCount;
    @org.spongepowered.asm.mixin.Unique private long phoenium$offeredTrackerTick = Long.MIN_VALUE;

    /** Explicit comparison input clock, opt-in only for the Stage 5 client fixture.
     * World freeze otherwise leaves this independent native tracker clock running
     * during variable client waits. Offer one unchanged original body per world
     * tick to the three fixture entities; never edit its counters or packets.
     * Unrestricted client campaigns and every production artifact omit this clock.
     */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "sendChanges()V")
    private void phoenium$comparisonClock(com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (!Boolean.getBoolean("phoenium.stage5.trackerClock") || !Stage4Trace.relevantEntity(entity)) {
            original.call(); return;
        }
        long offered = entity.level().getGameTime();
        if (offered == phoenium$offeredTrackerTick) return;
        if (offered < phoenium$offeredTrackerTick) throw new IllegalStateException("fixture tracker clock moved backwards");
        phoenium$offeredTrackerTick = offered;
        int before = tickCount;
        original.call();
        System.out.println("STAGE5_TRACKER_CLOCK uuid=" + entity.getUUID() + " gameTime=" + offered
            + " before=" + before + " after=" + tickCount);
    }
    @Inject(method = "addPairing(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$pair(ServerPlayer player, CallbackInfo ci) {
        if (!Stage4Trace.relevantEntity(entity)) return;
        Stage4Trace.event("tracking.pair", "recipient=" + player.getName().getString()
            + " entityId=" + entity.getId() + " uuid=" + entity.getUUID() + " gameTime=" + player.level().getGameTime() + " "
            + Stage4Trace.authority(player.level(), entity.blockPosition()));
        Stage4Trace.trackingBodyObserved(entity, "pair");
    }

    @Inject(method = "removePairing(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$remove(ServerPlayer player, CallbackInfo ci) {
        if (!Stage4Trace.relevantEntity(entity)) return;
        Stage4Trace.event("tracking.remove", "recipient=" + player.getName().getString()
            + " entityId=" + entity.getId() + " uuid=" + entity.getUUID() + " gameTime=" + player.level().getGameTime() + " "
            + Stage4Trace.authority(player.level(), entity.blockPosition()));
        Stage4Trace.trackingBodyObserved(entity, "remove");
    }

    @Inject(method = "sendChanges()V", at = @At("HEAD"), require = 1)
    private void phoenium$changes(CallbackInfo ci) { Stage4Trace.trackingChanges(entity); }
}
