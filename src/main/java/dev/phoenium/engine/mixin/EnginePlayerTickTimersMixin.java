package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineTimers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** S9-E10 (engine mode only): the players' own entity tick inside the connection tick for {@link EngineTimers}. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class EnginePlayerTickTimersMixin {
    @WrapOperation(method = "tickPlayer()Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;doTick()V"))
    private void phoenium$timePlayerTick(ServerPlayer player, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(player); } finally { EngineTimers.add(EngineTimers.PLAYER_TICKS, start); }
    }
}
