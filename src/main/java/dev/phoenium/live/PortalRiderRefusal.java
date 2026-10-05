package dev.phoenium.live;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.LoggerFactory;

/**
 * Conservative policy for the player-carrying graphs Phoenium cannot sequence yet: a non-player vehicle whose
 * passenger tree holds a player in any shape other than "vehicle + leaf players" (see {@link PortalRiders},
 * which is carried natively) does not take a portal. The managers fail the server when they meet such a graph. Declining the lookup is native's own null-destination path (Entity.handlePortal skips the
 * teleport, the portal cooldown is already set and the tick continues), so no tail needs unwinding. This is a
 * known behavioural limitation, counted and logged, and is not accepted portal behaviour.
 */
public final class PortalRiderRefusal {
    private static final AtomicLong REFUSED = new AtomicLong();

    private PortalRiderRefusal() { }

    public static boolean refuses(Entity entity) {
        // A throwable that carries a rider has no retained-continuation shape (a riding throwable does: it is dismounted).
        if (entity instanceof net.minecraft.world.entity.projectile.ThrowableProjectile && entity.isVehicle()) {
            long count = REFUSED.incrementAndGet();
            if (Boolean.getBoolean("phoenium.test.diagnostics")) {
                try {
                    LoggerFactory.getLogger(PortalRiderRefusal.class).info(
                        "PHOENIUM_PORTAL_LIMITATION reason=THROWABLE_CARRIES_RIDER type={} uuid={} total={}", entity.getType(), entity.getUUID(), count);
                } catch (Throwable ignored) { /* Diagnostics never replace the native outcome. */ }
            }
            return true;
        }
        if (entity instanceof Player || !entity.isVehicle() || !PortalRiders.carriesPlayer(entity)) return false;
        // "Vehicle + leaf player riders" is carried natively (see PortalRiders); everything else is declined.
        if (!PortalRiders.leafPlayerRiders(entity).isEmpty()) return false;
        long count = REFUSED.incrementAndGet();
        if (Boolean.getBoolean("phoenium.test.diagnostics")) {
            try {
                LoggerFactory.getLogger(PortalRiderRefusal.class).info(
                    "PHOENIUM_PORTAL_LIMITATION reason=PLAYER_RIDER_GRAPH type={} uuid={} total={}", entity.getType(), entity.getUUID(), count);
            } catch (Throwable ignored) { /* Diagnostics never replace the native outcome. */ }
        }
        return true;
    }

    public static long refusedTotal() { return REFUSED.get(); }
}
