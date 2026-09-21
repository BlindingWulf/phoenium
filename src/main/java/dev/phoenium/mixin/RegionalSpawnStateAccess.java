package dev.phoenium.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

@Mixin(NaturalSpawner.SpawnState.class)
public interface RegionalSpawnStateAccess {
    @Accessor("mobCategoryCounts") Object2IntOpenHashMap<MobCategory> phoenium$getMobCategoryCounts();
    @Accessor("spawnPotential") PotentialCalculator phoenium$getSpawnPotential();
    @Invoker("canSpawnForCategoryLocal") boolean phoenium$canSpawnLocal(MobCategory category, ChunkPos pos);
    @Invoker("canSpawn") boolean phoenium$canSpawn(EntityType<?> type, BlockPos pos, ChunkAccess chunk);
    @Invoker("afterSpawn") void phoenium$afterSpawn(Mob mob, ChunkAccess chunk);
}
