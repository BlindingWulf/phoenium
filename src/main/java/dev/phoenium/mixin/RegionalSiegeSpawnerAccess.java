package dev.phoenium.mixin;

import net.minecraft.world.entity.ai.village.VillageSiege;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Original world-service state; the spawn origin survives changes in the player cohort. */
@Mixin(VillageSiege.class)
public interface RegionalSiegeSpawnerAccess {
    @Accessor("hasSetupSiege") boolean phoenium$hasSetupSiege();
    @Accessor("spawnX") int phoenium$spawnX();
    @Accessor("spawnZ") int phoenium$spawnZ();
}
