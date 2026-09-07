package dev.phoenium.mixin;

import dev.phoenium.chunk.FullFinishingScoreboard;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact private transport identity belongs to the connection service, not the entity projection. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface FullFinishingConnectionMixin extends FullFinishingScoreboard.ConnectionAccess {
    @Accessor("connection") Connection phoenium$serviceConnection();
}
