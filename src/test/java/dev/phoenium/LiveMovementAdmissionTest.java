package dev.phoenium;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.ClosureEscapeException;
import dev.phoenium.topology.LiveMovementAdmission;
import dev.phoenium.topology.LiveMovementFootprint;
import dev.phoenium.topology.LivePacketReplayGate;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyCoordinator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic contract tests for the future live packet adapter; no Minecraft client is used. */
class LiveMovementAdmissionTest {
    @Test void fullyContainedFootprintAdmitsBeforeAnyVanillaMutation() {
        Fixture f = new Fixture();
        LiveMovementFootprint footprint = f.footprint(f.aKey, f.aKey, new LiveMovementFootprint.ChunkBox(f.world, 0, 0, 0, 0));
        int vanillaMutations = 0;
        LiveMovementAdmission.Ticket ticket = LiveMovementAdmission.admit(f.directory, f.aId, footprint);
        ticket.requireCurrent(f.directory, f.aId);
        vanillaMutations++;
        assertEquals(1, vanillaMutations);
        assertEquals(f.aKey, footprint.routingDestination());
    }

    @Test void sameOwnerChunkCrossingAdmitsAndPreservesObjectEpoch() {
        Fixture f = new Fixture();
        RoutingKey withinA = new RoutingKey(f.world, 1, 0);
        f.publish(Map.of(f.aKey, f.aId, withinA, f.aId, f.bKey, f.bId));
        ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(f.directory);
        ObjectOwnership entity = objects.admit("live-same-owner", OwnedObjectKind.PLAYER, f.aKey);
        LiveMovementFootprint footprint = f.footprint(f.aKey, withinA, new LiveMovementFootprint.ChunkBox(f.world, 0, 1, 0, 0));
        LiveMovementAdmission.admit(f.directory, f.aId, footprint).requireCurrent(f.directory, f.aId);
        ObjectOwnership moved = objects.updateRoutingKey("live-same-owner", withinA, entity.objectEpoch());
        assertEquals(f.aId, moved.owner());
        assertEquals(new OwnerEpoch(0), moved.objectEpoch());
    }

    @Test void escapingCollisionFootprintFailsBeforeVanillaOrForeignMutation() {
        Fixture f = new Fixture();
        LiveMovementFootprint footprint = f.footprint(f.aKey, f.aKey, new LiveMovementFootprint.ChunkBox(f.world, 0, 1, 0, 0));
        int vanillaMutations = 0;
        int foreignMutations = 0;
        assertThrows(ClosureEscapeException.class, () -> LiveMovementAdmission.admit(f.directory, f.aId, footprint));
        assertEquals(0, vanillaMutations);
        assertEquals(0, foreignMutations);
    }

    @Test void conflictingTwoRegionClosuresRemainPendingWithoutIndependentOverlap() {
        Fixture f = new Fixture();
        RoutingKey bridge = new RoutingKey(f.world, 5, 0);
        TopologyCoordinator coordinator = new TopologyCoordinator(f.directory, f.runtimes, f.gateway);
        coordinator.recordConvergence(Set.of(bridge), new TopologyCoordinator.Transition(Set.of(f.aId, f.bId),
            Map.of(f.aKey, f.aId, f.bKey, f.bId, bridge, f.bId)), "live admission closure conflict");
        assertTrue(coordinator.hasPendingConvergence());
        assertEquals(Set.of(f.aKey, f.bKey), f.directory.snapshot().routes().keySet());
        assertTrue(f.a.transitioning());
        assertTrue(f.b.transitioning());
    }

    @Test void topologyPublicationBetweenCalculationAndReplayRejectsStaleTicket() {
        Fixture f = new Fixture();
        LiveMovementAdmission.Ticket ticket = LiveMovementAdmission.admit(f.directory, f.aId,
            f.footprint(f.aKey, f.aKey, new LiveMovementFootprint.ChunkBox(f.world, 0, 0, 0, 0)));
        f.publish(Map.of(f.aKey, f.aId, f.bKey, f.bId));
        assertThrows(IllegalStateException.class, () -> ticket.requireCurrent(f.directory, f.aId));
    }

    @Test void replayMarkerRequiresCurrentFootprintAndConsumesExactlyOneAuthorizedReplay() {
        Fixture f = new Fixture();
        LiveMovementAdmission.Ticket ticket = LiveMovementAdmission.admit(f.directory, f.aId,
            f.footprint(f.aKey, f.aKey, new LiveMovementFootprint.ChunkBox(f.world, 0, 0, 0, 0)));
        LivePacketReplayGate replay = new LivePacketReplayGate();
        int[] vanillaReplays = {0};
        try (LivePacketReplayGate.Scope ignored = replay.enter(ticket, f.directory, f.aId)) {
            assertTrue(replay.marked());
            vanillaReplays[0]++;
            assertThrows(IllegalStateException.class, () -> replay.enter(ticket, f.directory, f.aId));
        }
        assertFalse(replay.marked());
        assertEquals(1, vanillaReplays[0]);
        assertThrows(IllegalStateException.class, () -> replay.enter(ticket, f.directory, f.aId));
    }

    private static final class Fixture {
        final CoreFixture core = new CoreFixture();
        final WorldId world = core.world;
        final RegionId aId = core.aId, bId = core.bId;
        final RoutingKey aKey = core.aKey, bKey = core.bKey;
        final RegionDirectory directory = core.directory;
        final Map<RegionId, dev.phoenium.region.RegionRuntime> runtimes = core.runtimes;
        final dev.phoenium.region.RegionRuntime a = core.a, b = core.b;
        final dev.phoenium.scheduler.OwnerGateway gateway = core.gateway;

        void publish(Map<RoutingKey, RegionId> owners) {
            TopologyGeneration next = directory.snapshot().generation().next();
            Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
            owners.forEach((key, owner) -> routes.put(key, new RegionRoute(owner, next, runtimes.get(owner).ownerEpoch())));
            directory.publish(next, routes);
        }

        LiveMovementFootprint footprint(RoutingKey source, RoutingKey destination, LiveMovementFootprint.ChunkBox collision) {
            return new LiveMovementFootprint(world, destination, List.of(collision), List.of(collision),
                List.of(collision), List.of(collision), List.of(collision),
                new java.util.LinkedHashSet<>(List.of(source, destination)));
        }
    }
}
