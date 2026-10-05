package dev.phoenium.live;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/**
 * The one player-rider shape Phoenium carries through a portal: a mob, boat or minecart whose direct
 * passengers are all connected players with no passengers of their own. Native then ejects every rider,
 * teleports each player (which Phoenium retains across its source/destination authority cut), creates the
 * replacement vehicle and remounts. Any other graph that carries a player is declined, see
 * {@link PortalRiderRefusal}.
 */
public final class PortalRiders {
    private PortalRiders() { }

    /** Vehicle classes that have a retained-portal manager and can legally carry a player. */
    public static boolean carriesPlayersNatively(Entity vehicle) {
        return vehicle instanceof Mob || vehicle instanceof AbstractBoat || vehicle instanceof AbstractMinecart;
    }

    /** The riders in native passenger order, or empty when the vehicle is not exactly "vehicle + leaf players". */
    public static List<ServerPlayer> leafPlayerRiders(Entity vehicle) {
        List<Entity> passengers = vehicle.getPassengers();
        if (passengers.isEmpty() || !carriesPlayersNatively(vehicle)) return List.of();
        for (Entity passenger : passengers)
            if (!(passenger instanceof ServerPlayer) || passenger.isVehicle()) return List.of();
        return passengers.stream().map(ServerPlayer.class::cast).toList();
    }

    /** True when a player sits anywhere in the passenger tree. */
    public static boolean carriesPlayer(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers())
            if (passenger instanceof Player || carriesPlayer(passenger)) return true;
        return false;
    }
}
