package dev.phoenium.engine.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** S9-E1: the holder lookup a tile thread uses for chunk requests (C2ME answers it from its concurrent holder map). */
@Mixin(ChunkMap.class)
public interface EngineChunkMapAccess {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder phoenium$visibleHolder(long key);
}
