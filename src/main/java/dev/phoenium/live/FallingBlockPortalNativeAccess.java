package dev.phoenium.live;

import net.minecraft.world.level.block.Block;

/** The pinned 26.2 falling-block tick tail retains its pre-portal Block local. */
public interface FallingBlockPortalNativeAccess {
    void phoenium$resumeFallingBlockTickSuffix(Block originalBlock);
}
