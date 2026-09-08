package dev.phoenium.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NaturalSpawner.SpawnState.class)
public interface RegionalSpawnStateAccess {
    @Accessor("mobCategoryCounts") Object2IntOpenHashMap<MobCategory> phoenium$getMobCategoryCounts();
    @Accessor("spawnPotential") PotentialCalculator phoenium$getSpawnPotential();
}
