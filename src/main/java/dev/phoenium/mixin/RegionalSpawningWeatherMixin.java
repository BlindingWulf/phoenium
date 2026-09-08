package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Reuses the owner-free weather observation that selected the exact spawning callback halo. */
@Mixin(ServerLevel.class)
abstract class RegionalSpawningWeatherMixin {
    @WrapOperation(method = "tickThunder", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
    private boolean phoenium$raining(ServerLevel level, Operation<Boolean> original, LevelChunk chunk) {
        if (!Phoenium.liveRuntime().regionalSpawningWeatherActive(level)) return original.call(level);
        return Phoenium.liveRuntime().regionalSpawningWeather(level, false);
    }
    @WrapOperation(method = "tickThunder", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;isThundering()Z"))
    private boolean phoenium$thundering(ServerLevel level, Operation<Boolean> original, LevelChunk chunk) {
        if (!Phoenium.liveRuntime().regionalSpawningWeatherActive(level)) return original.call(level);
        return Phoenium.liveRuntime().regionalSpawningWeather(level, true);
    }
}
