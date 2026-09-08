package dev.phoenium.mixin;

import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin({PhantomSpawner.class, PatrolSpawner.class, CatSpawner.class})
public interface RegionalTimedCustomSpawnerAccess {
    @Accessor("nextTick") int phoenium$nextTick();
}
