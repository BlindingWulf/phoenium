package dev.phoenium.service;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.LiveMovementFootprint;
import dev.phoenium.topology.RoutingKey;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionalMovementTrackingTest {
    @Test void movementIncludesMiddleOfSweptPathAndCappedReverseRecipients() {
        var world = new WorldId("movement-tracking");
        var box = new LiveMovementFootprint.ChunkBox(world, 7, 32, 0, 0);
        var movement = new LiveMovementFootprint(world, new RoutingKey(world, 32, 0),
            List.of(box), List.of(box), List.of(box), List.of(box), List.of(box), Set.of());
        assertTrue(RegionalTrackingService.movementCandidate(new RoutingKey(world, 0, 0), 128, movement));
        assertFalse(RegionalTrackingService.movementCandidate(new RoutingKey(world, 0, 0), 80, movement));
        assertTrue(RegionalTrackingService.movementCandidate(new RoutingKey(world, 20, 0), 16, movement));
        assertFalse(RegionalTrackingService.movementCandidate(new RoutingKey(world, 20, 12), 128, movement));
        var callback = new RoutingKey(world, 0, 0);
        var extended = movement.withCallbacks(Set.of(callback));
        assertTrue(extended.synchronousCallbackAccesses().contains(callback));
        assertEquals(movement.sweptCollision(), extended.sweptCollision());
        assertThrows(IllegalArgumentException.class, () -> movement.withCallbacks(Set.of(
            new RoutingKey(new WorldId("foreign"), 0, 0))));
    }
}
