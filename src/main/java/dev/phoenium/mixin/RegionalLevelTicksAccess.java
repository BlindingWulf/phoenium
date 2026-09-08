package dev.phoenium.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import java.util.function.LongPredicate;
import java.util.Queue;
import java.util.List;
import java.util.Set;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact pinned LevelTicks state used once to bind its real persistent chunk containers. */
@Mixin(LevelTicks.class)
public interface RegionalLevelTicksAccess<T> {
    @Accessor("tickCheck") LongPredicate phoenium$getTickCheck();
    @Accessor("allContainers") Long2ObjectMap<LevelChunkTicks<T>> phoenium$getAllContainers();
    @Accessor("nextTickForContainer") Long2LongMap phoenium$getNextTickForContainer();
    @Accessor("toRunThisTick") Queue<ScheduledTick<T>> phoenium$getToRunThisTick();
    @Accessor("alreadyRunThisTick") List<ScheduledTick<T>> phoenium$getAlreadyRunThisTick();
    @Accessor("toRunThisTickSet") Set<ScheduledTick<?>> phoenium$getToRunThisTickSet();
}
