package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
abstract class Stage4ServerEntityMixin {
    @Inject(method = "addPairing(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$pair(ServerPlayer player, CallbackInfo ci) { Stage4Trace.event("tracking.pair", "recipient=" + player.getName().getString()); }

    @Inject(method = "removePairing(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$remove(ServerPlayer player, CallbackInfo ci) { Stage4Trace.event("tracking.remove", "recipient=" + player.getName().getString()); }

    @Inject(method = "sendChanges()V", at = @At("HEAD"), require = 1)
    private void phoenium$changes(CallbackInfo ci) { Stage4Trace.event("tracking.changes"); }
}
