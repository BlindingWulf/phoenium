package dev.phoenium.service;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

/** Native access to a rebindable ticker's current delegate; no lifecycle state is retained. */
public interface WrappedBlockEntityTickerAccess {
    TickingBlockEntity phoenium$wrappedTicker();
}
