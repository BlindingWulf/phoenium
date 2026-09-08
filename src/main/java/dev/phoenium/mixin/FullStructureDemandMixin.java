package dev.phoenium.mixin;

import dev.phoenium.chunk.FullStructureDemand;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface FullStructureDemandMixin extends FullStructureDemand.CacheAccess {
    @Override @Accessor("ticketStorage") TicketStorage phoenium$structureDemandTickets();
    @Override @Invoker("runDistanceManagerUpdates") boolean phoenium$updateStructureDemand();
}
