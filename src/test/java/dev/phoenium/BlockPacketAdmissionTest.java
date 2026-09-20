package dev.phoenium;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.ClosureEscapeException;
import dev.phoenium.topology.LiveBlockPacketAdmission;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyPlanner;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deterministic block-packet target/closure admission tests; no native packet replay is faked. */
class BlockPacketAdmissionTest {
    @Test
    void targetAndImmediateCallbackHaloAdmitOnlyInsideOnePublishedOwner() {
        Fixture f = new Fixture(false);
        LiveBlockPacketAdmission.Ticket ticket = f.admit();
        ticket.requireCurrent(f.directory, f.objects, f.a);
        assertEquals(f.a, ticket.owner());
        assertEquals(f.footprint, ticket.footprint());
    }

    @Test
    void foreignTargetOrNeighborClosureFailsBeforeNativeMutation() {
        Fixture f = new Fixture(true);
        assertThrows(ClosureEscapeException.class, f::admit);
    }

    @Test
    void publishedTopologyGenerationInvalidatesTheRetainedPacket() {
        Fixture f = new Fixture(false);
        LiveBlockPacketAdmission.Ticket ticket = f.admit();
        f.publish(false, f.directory.snapshot().generation().next());
        assertThrows(IllegalStateException.class,
            () -> ticket.requireCurrent(f.directory, f.objects, f.a));
    }

    @Test
    void playerIncarnationAndObjectEpochCannotBeReusedByAnOldPacket() {
        Fixture f = new Fixture(false);
        LiveBlockPacketAdmission.Ticket ticket = f.admit();
        f.objects.remove("player");
        ObjectOwnership replacement = f.objects.admit("player", OwnedObjectKind.PLAYER, f.playerKey);
        assertNotEquals(ticket.playerIncarnation(), replacement.incarnation());
        assertNotEquals(ticket.playerEpoch(), replacement.objectEpoch());
        assertThrows(IllegalStateException.class,
            () -> ticket.requireCurrent(f.directory, f.objects, f.a));
    }

    @Test
    void footprintUsesTheSameFiniteClosureModelAsRegionalServices() {
        WorldId world = new WorldId("minecraft:overworld");
        RoutingKey player = new RoutingKey(world, 0, 0);
        RoutingKey target = new RoutingKey(world, 1, 0);
        Set<RoutingKey> actual = new TopologyPlanner().requiredClosure(Set.of(player, target), 1);
        Set<RoutingKey> expected = new LinkedHashSet<>();
        for (int x = -1; x <= 2; x++) for (int z = -1; z <= 1; z++) expected.add(new RoutingKey(world, x, z));
        assertEquals(expected, actual);
    }

    private static final class Fixture {
        final WorldId world = new WorldId("minecraft:overworld");
        final RoutingKey playerKey = new RoutingKey(world, 0, 0);
        final RoutingKey targetKey = new RoutingKey(world, 1, 0);
        final RegionId a = new RegionId(world, "A");
        final RegionId b = new RegionId(world, "B");
        final RegionDirectory directory = new RegionDirectory();
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final Set<RoutingKey> footprint = new TopologyPlanner().requiredClosure(Set.of(playerKey, targetKey), 1);

        Fixture(boolean foreignTarget) {
            publish(foreignTarget, new TopologyGeneration(1));
            objects.admit("player", OwnedObjectKind.PLAYER, playerKey);
        }

        LiveBlockPacketAdmission.Ticket admit() {
            return LiveBlockPacketAdmission.admit(directory, objects.current("player").orElseThrow(), footprint);
        }

        void publish(boolean foreignTarget, TopologyGeneration generation) {
            Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
            for (RoutingKey key : footprint)
                routes.put(key, new RegionRoute(foreignTarget && key.equals(targetKey) ? b : a,
                    generation, new OwnerEpoch(0)));
            routes.put(new RoutingKey(world, 5, 0), new RegionRoute(b, generation, new OwnerEpoch(0)));
            directory.publish(generation, routes);
        }
    }
}
