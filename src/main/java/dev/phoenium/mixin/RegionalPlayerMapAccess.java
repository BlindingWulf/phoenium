package dev.phoenium.mixin;

import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.server.level.PlayerMap")
public interface RegionalPlayerMapAccess {
    @Invoker("getAllPlayers") Set<ServerPlayer> phoenium$invokeAllPlayers();
}
