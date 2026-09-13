package dev.phoenium.live;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;

/** Version-pinned owner read for the 26.2 thrown-pearl association seam. */
public final class PearlOwnerAccess {
    private PearlOwnerAccess() { }

    /**
     * Reads the pearl's persisted {@code Projectile.owner} reference through the native public
     * accessor.  Only a live ServerPlayer owner enters the per-player policy; unowned and
     * non-player-owned pearls remain outside that policy.
     */
    public static UUID playerId(ThrownEnderpearl pearl) {
        Objects.requireNonNull(pearl, "pearl");
        Entity owner = pearl.getOwner();
        return owner instanceof ServerPlayer player ? player.getUUID() : null;
    }
}
