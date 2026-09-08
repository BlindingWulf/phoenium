package dev.phoenium.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface RegionalChunkMapAccess {
    @Accessor("level") ServerLevel phoenium$getLevel();
    @Accessor("entityMap") Int2ObjectMap<Object> phoenium$getTrackedEntities();
    @Accessor("playerMap") Object phoenium$getPlayerMap();
    @Accessor("distanceManager") Object phoenium$getDistanceManager();
    @Invoker("updateChunkTracking") void phoenium$invokeUpdateChunkTracking(ServerPlayer player);
}
