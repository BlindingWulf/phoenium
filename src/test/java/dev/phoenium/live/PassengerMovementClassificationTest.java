package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.util.Set;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * S8-F7: a riding player's ServerboundMovePlayerPacket is vanilla's only per-packet ChunkMap.move edge for
 * that rider (handleMovePlayer passenger branch). Rejecting it left the vehicle unpaired on the client after a
 * mounted portal arrival. The packet must classify at the rider's settled position, never at the packet position.
 */
final class PassengerMovementClassificationTest {
    static final WorldId NETHER = new WorldId("minecraft:the_nether");

    static LiveMovementSnapshot.State rider(double x, double y, double z, boolean vehicleInLevel) {
        return new LiveMovementSnapshot.State(NETHER, true, vehicleInLevel, false, false, false, x, y, z,
            new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3), 0.6);
    }

    static LiveMovementSnapshot.State walker(double x, double y, double z) {
        return new LiveMovementSnapshot.State(NETHER, false, false, false, false, false, x, y, z,
            new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3), 0.6);
    }

    @Test void riderPacketClassifiesAtSettledPositionAndIgnoresPacketAndLastGood() {
        // The client of a mounted portal arrival does not know it rides yet: it reports a standing position,
        // and the handler's lastGood still holds the pre-mount value. Vanilla reads neither.
        var snapshot = LiveMovementSnapshot.classify(rider(251.5, 34.6, 244.5, true),
            300.0, 40.0, 300.0, 240.0, 34.0, 240.0, false);
        assertEquals(LiveMovementSnapshot.Disposition.CLASSIFIED, snapshot.disposition());
        assertTrue(snapshot.passenger());
        assertEquals(MovementBootstrapDisposition.ADMIT, MovementBootstrapDisposition.from(snapshot.disposition()));
        RoutingKey settled = new RoutingKey(NETHER, 15, 15);
        assertEquals(settled, snapshot.footprint().routingDestination());
        assertEquals(Set.of(settled), snapshot.footprint().synchronousCallbackAccesses());
    }

    @Test void riderFootprintIsTheZeroLengthMoveAtItsPosition() {
        var rider = LiveMovementSnapshot.classify(rider(15.9, 70.0, -0.2, true), 99.0, 99.0, 99.0, 0, 0, 0, false);
        var still = LiveMovementSnapshot.classify(walker(15.9, 70.0, -0.2), 15.9, 70.0, -0.2, 15.9, 70.0, -0.2, false);
        assertTrue(still.classified());
        assertFalse(still.passenger());
        assertEquals(still.footprint(), rider.footprint());
    }

    @Test void riderWithoutAVehicleInItsLevelStaysRejected() {
        var snapshot = LiveMovementSnapshot.classify(rider(8.5, 70, 8.5, false), 8.5, 70, 8.5, 8.5, 70, 8.5, false);
        assertEquals(LiveMovementSnapshot.Disposition.PASSENGER, snapshot.disposition());
        assertNull(snapshot.footprint());
        assertEquals(MovementBootstrapDisposition.REJECT_UNCLASSIFIED, MovementBootstrapDisposition.from(snapshot.disposition()));
    }

    @Test void riderTransitionGuardsPrecedeTheRiderBranch() {
        assertEquals(LiveMovementSnapshot.Disposition.AWAITING_TELEPORT,
            LiveMovementSnapshot.classify(rider(8.5, 70, 8.5, true), 8.5, 70, 8.5, 8.5, 70, 8.5, true).disposition());
        var changing = new LiveMovementSnapshot.State(NETHER, true, true, false, false, true, 8.5, 70, 8.5,
            new AABB(8.2, 70, 8.2, 8.8, 71.8, 8.8), 0.6);
        assertEquals(LiveMovementSnapshot.Disposition.DIMENSION_TRANSITION,
            LiveMovementSnapshot.classify(changing, 8.5, 70, 8.5, 8.5, 70, 8.5, false).disposition());
        assertEquals(LiveMovementSnapshot.Disposition.NON_FINITE,
            LiveMovementSnapshot.classify(rider(Double.NaN, 70, 8.5, true), 8.5, 70, 8.5, 8.5, 70, 8.5, false).disposition());
    }

    @Test void walkingPlayerClassificationIsUnchanged() {
        assertEquals(LiveMovementSnapshot.Disposition.LAST_GOOD_MISMATCH,
            LiveMovementSnapshot.classify(walker(8.5, 70, 8.5), 9.0, 70, 8.5, 8.0, 70, 8.5, false).disposition());
        var moved = LiveMovementSnapshot.classify(walker(15.5, 70, 8.5), 16.5, 70, 8.5, 15.5, 70, 8.5, false);
        assertTrue(moved.classified());
        assertFalse(moved.passenger());
        assertEquals(new RoutingKey(NETHER, 1, 0), moved.footprint().routingDestination());
        assertEquals(Set.of(new RoutingKey(NETHER, 0, 0), new RoutingKey(NETHER, 1, 0)),
            moved.footprint().synchronousCallbackAccesses());
    }
}
