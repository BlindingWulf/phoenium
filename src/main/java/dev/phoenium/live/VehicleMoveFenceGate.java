package dev.phoenium.live;

import dev.phoenium.core.WorldId;

/**
 * Decides whether a vehicle-move packet may close a player's dimension-transition fence. A riding
 * player may send only vehicle-move packets (a Bedrock client through Geyser does), and only on-foot
 * movement packets used to close the fence, so the fence stayed open for as long as the player rode.
 * A vehicle move closes it under the conditions the on-foot path already requires.
 */
final class VehicleMoveFenceGate {
    private VehicleMoveFenceGate() { }

    /**
     * @param fenceWorld destination world of the open fence
     * @param playerWorld the world the player is actually in
     * @param awaitingTeleport the client has not yet acknowledged the server's last teleport
     * @param riding the player has a root vehicle
     * @param nearVehicle the packet's position is within the fence's rebase distance of that vehicle
     */
    static boolean mayClose(WorldId fenceWorld, WorldId playerWorld, boolean awaitingTeleport,
                            boolean riding, boolean nearVehicle) {
        return fenceWorld.equals(playerWorld) && !awaitingTeleport && riding && nearVehicle;
    }

    /**
     * Whether a replayed vehicle move may run the native handler on the player's root vehicle. Vanilla teleports a
     * vehicle atomically inside its tick and then ignores the client's vehicle moves until the teleport is acknowledged.
     * Under Phoenium the capture is that instant: a move replayed after it is retired without touching the vehicle, which
     * stays frozen at its capture pose until the transfer settles (or is cancelled; the next move then applies normally).
     *
     * @param rootVehiclePortalRetained the root vehicle's portal transfer is captured and not yet settled
     */
    static boolean mayApply(boolean rootVehiclePortalRetained) {
        return !rootVehiclePortalRetained;
    }

    /** The owner binding must already be destination-world authoritative before the fence closes. */
    static boolean ownerReady(WorldId ownerWorld, WorldId playerWorld) {
        return ownerWorld != null && ownerWorld.equals(playerWorld);
    }
}
