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

    @Test void ownerMustAlreadyBeInThePlayersWorld() {
        assertTrue(VehicleMoveFenceGate.ownerReady(OVERWORLD, OVERWORLD));
        assertFalse(VehicleMoveFenceGate.ownerReady(NETHER, OVERWORLD));
        assertFalse(VehicleMoveFenceGate.ownerReady(null, OVERWORLD));
    }
}
