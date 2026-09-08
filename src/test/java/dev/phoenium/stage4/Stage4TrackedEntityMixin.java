package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class Stage4TrackedEntityMixin {
    @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$recipient(ServerPlayer player, CallbackInfo ci) { Stage4Trace.event("tracking.recipient", "player=" + player.getName().getString()); }

    @Inject(method = "removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$recipientRemove(ServerPlayer player, CallbackInfo ci) { Stage4Trace.event("tracking.recipient.remove", "player=" + player.getName().getString()); }
}
