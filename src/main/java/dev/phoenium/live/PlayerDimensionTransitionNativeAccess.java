package dev.phoenium.live;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Pinned 26.2 suffixes retained across the player dimension authority cut. */
public final class PlayerDimensionTransitionNativeAccess {
    private PlayerDimensionTransitionNativeAccess() { }

    public interface PlayerSuffix {
        void phoenium$resumePlayerDimensionChange(TeleportTransition transition, ServerLevel oldLevel);
    }

    public interface PearlHitSuffix {
        void phoenium$resumePearlPlayerHit(ServerPlayer player, ServerLevel destination, Vec3 teleportPosition);
    }
}
