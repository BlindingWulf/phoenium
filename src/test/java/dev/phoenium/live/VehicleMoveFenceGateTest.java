package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.WorldId;
import org.junit.jupiter.api.Test;

final class VehicleMoveFenceGateTest {
    private static final WorldId NETHER = new WorldId("minecraft:the_nether");
    private static final WorldId OVERWORLD = new WorldId("minecraft:overworld");

    /** The Bedrock rider case: only vehicle moves arrive, the rebased client rides in the destination world. */
    @Test void rebasedVehicleMoveInTheDestinationWorldClosesTheFence() {
        assertTrue(VehicleMoveFenceGate.mayClose(OVERWORLD, OVERWORLD, false, true, true));
    }

    @Test void vehicleMoveBeforeTheDestinationWorldIsReachedKeepsTheFence() {
        assertFalse(VehicleMoveFenceGate.mayClose(NETHER, OVERWORLD, false, true, true));
    }

    @Test void unacknowledgedTeleportKeepsTheFence() {
        assertFalse(VehicleMoveFenceGate.mayClose(OVERWORLD, OVERWORLD, true, true, true));
    }

    @Test void playerWithoutAVehicleKeepsTheFence() {
        assertFalse(VehicleMoveFenceGate.mayClose(OVERWORLD, OVERWORLD, false, false, true));
    }

    /** A stale packet still carries source-world vehicle coordinates. */
    @Test void vehicleMoveFarFromTheRiddenVehicleKeepsTheFence() {
        assertFalse(VehicleMoveFenceGate.mayClose(OVERWORLD, OVERWORLD, false, true, false));
    }

    /**
     * S8-F7: a rider-controlled vehicle (carrot-on-a-stick pig, horse, boat; a Bedrock rider through Geyser) keeps sending
     * vehicle moves while its portal transfer is retained. Vanilla teleports atomically inside the vehicle's tick and then
     * ignores the client's vehicle moves until the teleport is acknowledged, so a move replayed after the capture must not
     * move the captured vehicle; applying it made the transfer stale and vanilla's portal cooldown blocked every retry.
     */
    @Test void vehicleMoveDoesNotMoveAVehicleWithARetainedPortalTransfer() {
        assertFalse(VehicleMoveFenceGate.mayApply(true));
    }

    @Test void vehicleMoveAppliesToAVehicleWithoutAPortalTransfer() {
        assertTrue(VehicleMoveFenceGate.mayApply(false));
    }

    @Test void ownerMustAlreadyBeInThePlayersWorld() {
        assertTrue(VehicleMoveFenceGate.ownerReady(OVERWORLD, OVERWORLD));
        assertFalse(VehicleMoveFenceGate.ownerReady(NETHER, OVERWORLD));
        assertFalse(VehicleMoveFenceGate.ownerReady(null, OVERWORLD));
    }
}
