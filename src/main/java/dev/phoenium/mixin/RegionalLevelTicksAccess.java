package dev.phoenium.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.function.LongPredicate;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact pinned LevelTicks state used once to bind its real persistent chunk containers. */
@Mixin(LevelTicks.class)
public interface RegionalLevelTicksAccess<T> {
    @Accessor("tickCheck") LongPredicate phoenium$getTickCheck();
    @Accessor("allContainers") Long2ObjectMap<LevelChunkTicks<T>> phoenium$getAllContainers();
}
