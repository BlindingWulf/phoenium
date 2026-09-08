package dev.phoenium.mixin;

import java.util.List;
import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface RegionalTrackedEntityAccess {
    @Accessor("entity") Entity phoenium$getEntity();
    @Accessor("serverEntity") ServerEntity phoenium$getServerEntity();
    @Accessor("lastSectionPos") SectionPos phoenium$getLastSectionPos();
    @Accessor("lastSectionPos") void phoenium$setLastSectionPos(SectionPos position);
    @Accessor("seenBy") Set<ServerPlayerConnection> phoenium$getSeenBy();
    @Invoker("getEffectiveRange") int phoenium$invokeEffectiveRange();
    @Invoker("updatePlayers") void phoenium$invokeUpdatePlayers(List<ServerPlayer> players);
    @Invoker("updatePlayer") void phoenium$invokeUpdatePlayer(ServerPlayer player);
}
