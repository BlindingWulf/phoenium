package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Publishes a candidate only after vanilla has made the entity addressable. */
@Mixin(ServerLevel.class)
abstract class ServerLevelEntityLifecycleMixin {
    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("TAIL"), require = 1)
    private void phoenium$freshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) Phoenium.liveRuntime().onEntityAddressable(entity);
    }

    @Inject(method = "addDuringTeleport(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"), require = 1)
    private void phoenium$teleportedEntity(Entity entity, CallbackInfo ci) {
        Phoenium.liveRuntime().onEntityAddressable(entity);
    }

    @Inject(method = "addNewPlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("TAIL"), require = 1)
    private void phoenium$addedPlayer(ServerPlayer player, CallbackInfo ci) {
        Phoenium.liveRuntime().onEntityAddressable(player);
    }
}
