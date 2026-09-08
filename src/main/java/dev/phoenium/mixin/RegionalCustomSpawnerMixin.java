package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps ServerLevel's installed custom-spawner list and exact callback order. Each concrete
 * callback crosses the owner-free list boundary independently. */
@Mixin(ServerLevel.class)
abstract class RegionalCustomSpawnerMixin {
    @WrapOperation(method = "tickCustomSpawners", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/CustomSpawner;tick(Lnet/minecraft/server/level/ServerLevel;Z)V"))
    private void phoenium$customSpawner(CustomSpawner spawner, ServerLevel level, boolean spawnEnemies,
            Operation<Void> original) {
        Phoenium.liveRuntime().regionalCustomSpawnerCallback(level, spawner, spawnEnemies,
            () -> original.call(spawner, level, spawnEnemies));
    }
}
