package dev.phoenium.service;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

/**
 * Narrow compatibility recognition for Lithium's sleeping block-entity sentinel.
 *
 * Lithium keeps the vanilla rebindable wrapper in the level ticker list and replaces only its
 * current delegate. The sentinel has no position by design, so its identity must be checked on
 * the delegate rather than inferred from any arbitrary null position.
 */
public final class LithiumSleepingTickerBridge {
    private static final String SLEEPING_SENTINEL =
            "net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity$1";
    private static final String SLEEPING_TYPE = "<lithium_sleeping>";

    private LithiumSleepingTickerBridge() { }

    /** True only while a Phoenium-exposed rebindable wrapper delegates to Lithium's sentinel. */
    public static boolean isSleepingSentinel(TickingBlockEntity ticker) {
        if (!(ticker instanceof WrappedBlockEntityTickerAccess access)) return false;
        TickingBlockEntity delegate = access.phoenium$wrappedTicker();
        return delegate != null
                && SLEEPING_SENTINEL.equals(delegate.getClass().getName())
                && SLEEPING_TYPE.equals(delegate.getType());
    }
}
