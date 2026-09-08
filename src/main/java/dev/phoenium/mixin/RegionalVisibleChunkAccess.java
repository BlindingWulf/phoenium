package dev.phoenium.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface RegionalVisibleChunkAccess {
    @Invoker("getVisibleChunkIfPresent") ChunkHolder phoenium$visible(long key);
}
