package dev.phoenium.engine.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** S9-E3: one player's local mob counts (LocalMobCapCalculator.MobCounts is private), read from a tile. */
@Mixin(targets = "net.minecraft.world.level.LocalMobCapCalculator$MobCounts")
public interface EngineMobCountsAccess {
    @Accessor("counts")
    Object2IntMap<MobCategory> phoenium$counts();
}
