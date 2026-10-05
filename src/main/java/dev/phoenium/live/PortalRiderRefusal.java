package dev.phoenium.live;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.LoggerFactory;

/**
 * Interim, deliberately conservative policy: a non-player vehicle that carries a player (anywhere in its passenger
 * tree) does not take a portal. Native moves the whole mount; Phoenium cannot yet sequence the rider's
 * asynchronous session transition around the vehicle's native transfer, and the managers fail the server when
 * they meet such a graph. Declining the lookup is native's own null-destination path (Entity.handlePortal skips the
 * teleport, the portal cooldown is already set and the tick continues), so no tail needs unwinding. This is a
 * known behavioural limitation, counted and logged, and is not accepted portal behaviour.
 */
public final class PortalRiderRefusal {
    private static final AtomicLong REFUSED = new AtomicLong();

    private PortalRiderRefusal() { }

    public static boolean refuses(Entity entity) {
        if (entity instanceof Player || !entity.isVehicle() || !hasPlayerPassenger(entity)) return false;
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

    private static boolean hasPlayerPassenger(Entity entity) {
        for (Entity passenger : entity.getPassengers())
            if (passenger instanceof Player || hasPlayerPassenger(passenger)) return true;
        return false;
    }
}
