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

    /** S9-D4 pre-filter: a range projection's visible range never exceeds the server view distance, so an
     * entity rejected at that bound must be rejected at every smaller range, for both selection predicates. */
    @Test void selectionAtTheViewDistanceBoundCoversEverySmallerRange() {
        var world = new WorldId("movement-bound");
        var random = new java.util.Random(0x5904L);
        for (int trial = 0; trial < 2_000; trial++) {
            int viewDistance = 2 + random.nextInt(31);
            int bound = viewDistance * 16;
            var player = new RoutingKey(world, random.nextInt(81) - 40, random.nextInt(81) - 40);
            var entity = new RoutingKey(world, random.nextInt(161) - 80, random.nextInt(161) - 80);
            long x0 = random.nextInt(41) - 20, z0 = random.nextInt(41) - 20;
            var box = new LiveMovementFootprint.ChunkBox(world, x0, x0 + random.nextInt(6), z0, z0 + random.nextInt(6));
            var movement = new LiveMovementFootprint(world, player,
                List.of(box), List.of(box), List.of(box), List.of(box), List.of(box), Set.of());
            boolean chunkAtBound = RegionalTrackingService.candidateChunk(entity, bound, player);
            boolean movementAtBound = RegionalTrackingService.movementCandidate(entity, bound, movement);
            for (int range = 0; range <= bound; range += 1 + random.nextInt(24)) {
                if (!chunkAtBound) assertFalse(RegionalTrackingService.candidateChunk(entity, range, player));
                if (!movementAtBound) assertFalse(RegionalTrackingService.movementCandidate(entity, range, movement));
            }
        }
    }
}
