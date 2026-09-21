package com.ishland.c2me.rewrites.chunksystem.common;

import com.ishland.flowsched.scheduler.ItemHolder;
import net.minecraft.world.level.ChunkPos;

/** Test-only center-holder lookup matching the locked C2ME provider surface. */
public final class TheChunkSystem {
    private ItemHolder center;
    public TheChunkSystem(ItemHolder center) { this.center = center; }
    public void replaceHolder(ItemHolder replacement) { center = replacement; }
    public ItemHolder getHolder(Object key) {
        return center != null && center.getKey().equals(key) ? center : null;
    }
}
