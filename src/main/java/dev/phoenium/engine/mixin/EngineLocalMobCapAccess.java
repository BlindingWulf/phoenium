package dev.phoenium.engine.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** S9-E3: the local mob cap's players-near cache and per-player counts, read (never written) from a tile. */
@Mixin(LocalMobCapCalculator.class)
public interface EngineLocalMobCapAccess {
    @Accessor("playersNearChunk")
    Long2ObjectMap<List<ServerPlayer>> phoenium$playersNear();

    @Accessor("playerMobCounts")
    Map<ServerPlayer, ?> phoenium$playerMobCounts();

    @Accessor("chunkMap")
    ChunkMap phoenium$chunkMap();
}
