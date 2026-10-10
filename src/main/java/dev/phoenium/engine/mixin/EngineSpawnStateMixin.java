package dev.phoenium.engine.mixin;

import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-E3 (engine mode only): natural spawning from a tile reads the tick's shared spawn state and keeps its own spawns in
 * the tile's view ({@link dev.phoenium.engine.EngineSpawnOverlay}); its spawns reach the shared state after the colour.
 */
@Mixin(NaturalSpawner.SpawnState.class)
abstract class EngineSpawnStateMixin {
    @Inject(method = "canSpawn", at = @At("HEAD"), cancellable = true)
    private void phoenium$tileCanSpawn(EntityType<?> type, BlockPos testPos, ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        TileContext context = TileEngine.current();
        if (context != null) cir.setReturnValue(context.spawn((NaturalSpawner.SpawnState) (Object) this).canSpawn(type, testPos, chunk));
    }

    @Inject(method = "afterSpawn", at = @At("HEAD"), cancellable = true)
    private void phoenium$tileAfterSpawn(Mob mob, ChunkAccess chunk, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        context.spawn((NaturalSpawner.SpawnState) (Object) this).afterSpawn(context, mob, chunk);
        ci.cancel();
    }

    @Inject(method = "canSpawnForCategoryLocal", at = @At("HEAD"), cancellable = true)
    private void phoenium$tileLocalCap(MobCategory category, ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        TileContext context = TileEngine.current();
        if (context != null) cir.setReturnValue(context.spawn((NaturalSpawner.SpawnState) (Object) this).canSpawnLocal(category, chunkPos));
    }
}
