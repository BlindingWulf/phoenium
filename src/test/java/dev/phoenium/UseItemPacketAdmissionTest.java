package dev.phoenium;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.LivePlayerPacketAdmission;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Target-free UseItem admission retains only exact player identity and current owner authority. */
final class UseItemPacketAdmissionTest {
    @Test
    void currentPlayerOwnerAdmitsTargetFreeItemUse() {
        Fixture f = new Fixture();
        LivePlayerPacketAdmission.Ticket ticket = f.admit();
        assertEquals(f.owner, ticket.owner());
        assertDoesNotThrow(() -> ticket.requireCurrent(f.directory, f.objects, f.owner));
    }

    @Test
    void wrongOwnerFailsClosedBeforeNativeItemUse() {
        Fixture f = new Fixture();
        RegionId foreign = new RegionId(f.world, "foreign");
        assertThrows(IllegalStateException.class,
            () -> f.admit().requireCurrent(f.directory, f.objects, foreign));
    }

    @Test
    void replacedPlayerCannotReuseQueuedItemUse() {
        Fixture f = new Fixture();
        LivePlayerPacketAdmission.Ticket ticket = f.admit();
        f.objects.remove("player");
        f.objects.admit("player", OwnedObjectKind.PLAYER, f.key);
        assertThrows(IllegalStateException.class,
            () -> ticket.requireCurrent(f.directory, f.objects, f.owner));
    }

    @Test
    void topologyChangeInvalidatesQueuedItemUse() {
        Fixture f = new Fixture();
        LivePlayerPacketAdmission.Ticket ticket = f.admit();
        f.publish(new TopologyGeneration(2));
        assertThrows(IllegalStateException.class,
            () -> ticket.requireCurrent(f.directory, f.objects, f.owner));
    }

    private static final class Fixture {
        final WorldId world = new WorldId("minecraft:overworld");
        final RoutingKey key = new RoutingKey(world, 0, 0);
        final RegionId owner = new RegionId(world, "item-owner");
        final RegionDirectory directory = new RegionDirectory();
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);

        Fixture() {
            publish(new TopologyGeneration(1));
            objects.admit("player", OwnedObjectKind.PLAYER, key);
        }

        LivePlayerPacketAdmission.Ticket admit() {
            return LivePlayerPacketAdmission.admit(directory, objects.current("player").orElseThrow());
        }

        void publish(TopologyGeneration generation) {
            directory.publish(generation, Map.of(key, new RegionRoute(owner, generation, new OwnerEpoch(0))));
        }
    }
}
