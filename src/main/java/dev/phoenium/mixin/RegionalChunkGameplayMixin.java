package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerChunkCache.class)
abstract class RegionalChunkGameplayMixin {
    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private NaturalSpawner.SpawnState phoenium$spawnState(int count, Iterable<Entity> entities,
            NaturalSpawner.ChunkGetter chunks, LocalMobCapCalculator caps,
            Operation<NaturalSpawner.SpawnState> original) {
        return Phoenium.liveRuntime().regionalSpawnState((ServerChunkCache) (Object) this,
            count, entities, chunks, (c, e, g, l) -> original.call(c, e, g, l));
    }

    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;collectSpawningChunks(Ljava/util/List;)V"))
    private void phoenium$spawningChunks(ChunkMap map, List<LevelChunk> output, Operation<Void> original) {
        Phoenium.liveRuntime().regionalCollectSpawningChunks((ServerChunkCache) (Object) this, map, output);
    }

    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerChunkCache;tickSpawningChunk(Lnet/minecraft/world/level/chunk/LevelChunk;JLjava/util/List;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;)V"))
    private void phoenium$spawningChunk(ServerChunkCache cache, LevelChunk chunk, long timeDiff,
            List<MobCategory> categories, NaturalSpawner.SpawnState state, Operation<Void> original) {
        Phoenium.liveRuntime().regionalSpawningChunkCallback(cache, chunk,
            () -> original.call(cache, chunk, timeDiff, categories, state));
    }

    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"))
    private void phoenium$blockTickingChunks(ChunkMap map, Consumer<LevelChunk> output, Operation<Void> original) {
        Phoenium.liveRuntime().regionalForEachBlockTickingChunk((ServerChunkCache) (Object) this, map, output);
    }

    @WrapOperation(method = "broadcastChangedChunks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkHolder;broadcastChanges(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void phoenium$broadcastChunk(ChunkHolder holder, LevelChunk chunk, Operation<Void> original) {
        ServerChunkCache cache = (ServerChunkCache) (Object) this;
        Phoenium.liveRuntime().regionalBroadcastChunkCallback(cache, chunk, () -> original.call(holder, chunk));
    }
}
