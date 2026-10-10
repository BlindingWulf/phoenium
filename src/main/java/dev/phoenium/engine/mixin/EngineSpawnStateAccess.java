package dev.phoenium.engine.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** S9-E3: the shared parts of a tick's spawn state, read by a tile's spawn view and updated by its deferred spawns. */
@Mixin(NaturalSpawner.SpawnState.class)
public interface EngineSpawnStateAccess {
    @Accessor("spawnPotential")
    PotentialCalculator phoenium$potential();

    @Accessor("mobCategoryCounts")
    Object2IntOpenHashMap<MobCategory> phoenium$mobCategoryCounts();

    @Accessor("localMobCapCalculator")
    LocalMobCapCalculator phoenium$localMobCaps();
}
