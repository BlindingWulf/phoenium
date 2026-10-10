package dev.phoenium.engine.mixin;

import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** S9-E4: what the engine's tracker filter reads of a ChunkMap.TrackedEntity, and its per-player update. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface EngineTrackedEntityAccess {
    @Accessor("entity")
    Entity phoenium$entity();

    @Accessor("seenBy")
    Set<ServerPlayerConnection> phoenium$seenBy();

    @Invoker("updatePlayer")
    void phoenium$updatePlayer(ServerPlayer player);
}
