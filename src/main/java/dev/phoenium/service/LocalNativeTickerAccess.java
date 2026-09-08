package dev.phoenium.service;

import net.minecraft.world.level.block.entity.BlockEntity;

/** Native ticker incarnation observation; called only inside the current owner. */
public interface LocalNativeTickerAccess {
    BlockEntity phoenium$localBlockEntity();
}
