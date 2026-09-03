package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Stage 2's sole live-tick interception: the one ServerLevel.tick call in MinecraftServer.tickChildren. */
@Mixin(targets = "net.minecraft.server.MinecraftServer")
abstract class MinecraftServerMixin {
    @Redirect(
        method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V")
    )
    private void phoenium$dispatchServerLevelTick(ServerLevel level, BooleanSupplier shouldKeepTicking) {
        Phoenium.liveRuntime().dispatchLevelTick(level, shouldKeepTicking);
    }
}
