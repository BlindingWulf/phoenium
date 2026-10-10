package dev.phoenium.engine.mixin;

import java.util.List;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** S9-E3: a tile runs the recorded tickSpawningChunk calls of its chunks through this (the call is private). */
@Mixin(ServerChunkCache.class)
public interface EngineChunkCacheAccess {
    @Invoker("tickSpawningChunk")
    void phoenium$tickSpawningChunk(LevelChunk chunk, long timeDiff, List<MobCategory> spawningCategories,
                                    NaturalSpawner.SpawnState spawnCookie);
}
