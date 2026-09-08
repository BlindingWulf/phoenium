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
    @Inject(method = "addPairing(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$pair(ServerPlayer player, CallbackInfo ci) {
        if (!Stage4Trace.relevantEntity(entity)) return;
        Stage4Trace.event("tracking.pair", "recipient=" + player.getName().getString()
            + " entityId=" + entity.getId() + " gameTime=" + player.level().getGameTime() + " "
            + Stage4Trace.authority(player.level(), entity.blockPosition()));
        Stage4Trace.trackingBodyObserved(entity, "pair");
    }

    @Inject(method = "removePairing(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$remove(ServerPlayer player, CallbackInfo ci) {
        if (!Stage4Trace.relevantEntity(entity)) return;
        Stage4Trace.event("tracking.remove", "recipient=" + player.getName().getString()
            + " entityId=" + entity.getId() + " gameTime=" + player.level().getGameTime() + " "
            + Stage4Trace.authority(player.level(), entity.blockPosition()));
        Stage4Trace.trackingBodyObserved(entity, "remove");
    }

    @Inject(method = "sendChanges()V", at = @At("HEAD"), require = 1)
    private void phoenium$changes(CallbackInfo ci) { Stage4Trace.trackingChanges(entity); }
}
