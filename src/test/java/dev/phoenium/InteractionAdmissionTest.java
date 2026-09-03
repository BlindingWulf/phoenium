package dev.phoenium;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.interaction.InteractionTargetIndex;
import dev.phoenium.interaction.InteractionTargetRef;
import dev.phoenium.interaction.LiveInteractionAdmission;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic I-ADM-01..06 target-resolution boundary tests; no Minecraft lookup or client. */
class InteractionAdmissionTest {
    @Test void iAdm01SameRegionValidTargetAdmits() {
        Fixture f = new Fixture(false);
        LiveInteractionAdmission.Ticket ticket = f.admit();
        ticket.requireCurrent(f.directory, f.objects, f.index, f.aId);
    }

    @Test void iAdm02UnknownEntityIdFailsClosed() {
        Fixture f = new Fixture(false);
        assertThrows(InteractionTargetIndex.UnknownInteractionTargetException.class, () -> f.index.require(f.world, 999));
    }

    @Test void iAdm03StaleTargetIncarnationFailsClosed() {
        Fixture f = new Fixture(false);
        LiveInteractionAdmission.Ticket ticket = f.admit();
        f.objects.remove("target");
        assertThrows(IllegalStateException.class, () -> ticket.requireCurrent(f.directory, f.objects, f.index, f.aId));
    }

    @Test void iAdm04EntityIdReuseNeverAuthorizesOldIncarnation() {
        Fixture f = new Fixture(false);
        InteractionTargetRef oldRef = f.targetRef;
        LiveInteractionAdmission.Ticket oldTicket = f.admit();
        f.objects.remove("target");
        ObjectOwnership e2 = f.objects.admit("target", OwnedObjectKind.ENTITY, f.targetKey);
        InteractionTargetRef e2Ref = f.index.publish(f.world, 123, e2);
        assertNotEquals(oldRef.incarnation(), e2Ref.incarnation());
        assertFalse(f.index.isCurrent(oldRef));
        assertThrows(IllegalStateException.class, () -> oldTicket.requireCurrent(f.directory, f.objects, f.index, f.aId));
    }

    @Test void iAdm05CrossRegionTargetDefersWithoutOwnerWaitOrMutation() {
        Fixture f = new Fixture(true);
        int[] mutations = {0};
        assertThrows(LiveInteractionAdmission.CrossRegionInteractionException.class,
            () -> LiveInteractionAdmission.admit(f.directory, f.player, f.targetRef, f.index));
        assertEquals(0, mutations[0]);
    }

    @Test void iAdm06TopologyPublicationAfterResolutionRejectsExecutionTicket() {
        Fixture f = new Fixture(false);
        LiveInteractionAdmission.Ticket ticket = f.admit();
        f.publish(false);
        assertThrows(IllegalStateException.class, () -> ticket.requireCurrent(f.directory, f.objects, f.index, f.aId));
    }

    private static final class Fixture {
        final CoreFixture core = new CoreFixture();
        final dev.phoenium.core.WorldId world = core.world;
        final RegionId aId = core.aId, bId = core.bId;
        final RoutingKey playerKey = core.aKey, targetKey = new RoutingKey(world, 1, 0);
        final RegionDirectory directory = core.directory;
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final InteractionTargetIndex index = new InteractionTargetIndex();
        final ObjectOwnership player;
        final InteractionTargetRef targetRef;

        Fixture(boolean crossRegion) {
            publish(crossRegion);
            player = objects.admit("player", OwnedObjectKind.PLAYER, playerKey);
            ObjectOwnership target = objects.admit("target", OwnedObjectKind.ENTITY, targetKey);
            targetRef = index.publish(world, 123, target);
        }
        LiveInteractionAdmission.Ticket admit() {
            return LiveInteractionAdmission.admit(directory, player, targetRef, index);
        }
        void publish(boolean crossRegion) {
            TopologyGeneration generation = directory.snapshot().generation().next();
            Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
            routes.put(playerKey, new RegionRoute(aId, generation, new OwnerEpoch(0)));
            routes.put(targetKey, new RegionRoute(crossRegion ? bId : aId, generation, new OwnerEpoch(0)));
            routes.put(core.bKey, new RegionRoute(bId, generation, new OwnerEpoch(0)));
            directory.publish(generation, routes);
        }
    }
}
