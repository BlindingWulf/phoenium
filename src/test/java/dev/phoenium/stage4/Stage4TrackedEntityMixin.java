package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class Stage4TrackedEntityMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.entity.Entity entity;
    @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$recipient(ServerPlayer player, CallbackInfo ci) { if (!Stage4Trace.relevantEntity(entity)) return; Stage4Trace.event("tracking.recipient", "player=" + player.getName().getString()
        + " gameTime=" + player.level().getGameTime()); }

    @Inject(method = "removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$recipientRemove(ServerPlayer player, CallbackInfo ci) { if (!Stage4Trace.relevantEntity(entity)) return; Stage4Trace.event("tracking.recipient.remove", "player=" + player.getName().getString()
        + " gameTime=" + player.level().getGameTime()); }
}
