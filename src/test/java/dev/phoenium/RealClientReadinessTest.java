package dev.phoenium;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.interaction.InteractionTargetIndex;
import dev.phoenium.interaction.InteractionTargetRef;
import dev.phoenium.interaction.LiveInteractionAdmission;
import dev.phoenium.live.EntityIndexLifecycleAdapter;
import dev.phoenium.live.ExplicitLiveTopologyFixture;
import dev.phoenium.live.LiveMovementSnapshot;
import dev.phoenium.live.MovementBootstrapDisposition;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.FrozenRegionAuthority;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RoutingKey;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic real-client readiness tests; these construct no Minecraft client or packet. */
class RealClientReadinessTest {
    @Test void firstMovementBootstrapDefersOnlyAwaitingTeleportOrBaselineMismatch() {
        assertEquals(MovementBootstrapDisposition.DEFER_UNTIL_TELEPORT_OR_BASELINE_CONVERGES,
            MovementBootstrapDisposition.from(LiveMovementSnapshot.Disposition.AWAITING_TELEPORT));
        assertEquals(MovementBootstrapDisposition.DEFER_UNTIL_TELEPORT_OR_BASELINE_CONVERGES,
            MovementBootstrapDisposition.from(LiveMovementSnapshot.Disposition.LAST_GOOD_MISMATCH));
        assertEquals(MovementBootstrapDisposition.REJECT_UNCLASSIFIED,
            MovementBootstrapDisposition.from(LiveMovementSnapshot.Disposition.PASSENGER));
        assertEquals(MovementBootstrapDisposition.ADMIT,
            MovementBootstrapDisposition.from(LiveMovementSnapshot.Disposition.CLASSIFIED));
    }

    @Test void explicitFixtureHasDisjointAAndBAuthorityAndNoWorldDefaultFallback() {
        WorldId world = new WorldId("minecraft:overworld");
        RoutingKey anchor = new RoutingKey(world, 0, 0);
        ExplicitLiveTopologyFixture.Plan plan = ExplicitLiveTopologyFixture.plan(world, anchor,
            new TopologyGeneration(1), new OwnerEpoch(0), new OwnerEpoch(0));
        RegionDirectory directory = new RegionDirectory();
        directory.publish(new TopologyGeneration(1), plan.routes(), Map.of());
        FrozenRegionAuthority a = FrozenRegionAuthority.freeze(directory, plan.a());
        FrozenRegionAuthority b = FrozenRegionAuthority.freeze(directory, plan.b());
        assertEquals(81, a.domain().size());
        assertEquals(36, b.domain().size());
        assertTrue(java.util.Collections.disjoint(a.domain(), b.domain()));
        assertTrue(directory.snapshot().worldDefaults().isEmpty());
        assertEquals(plan.a(), directory.resolve(new RoutingKey(world, 1, 0)).orElseThrow().region());
        assertEquals(plan.b(), directory.resolve(new RoutingKey(world, 5, 0)).orElseThrow().region());
        assertTrue(directory.resolve(new RoutingKey(world, 20, 0)).isEmpty());
    }

    @Test void firstPlayerOwnershipExistsBeforeMovementIngressAndCannotUseDefaultFallback() {
        Fixture f = new Fixture();
        ObjectOwnership player = f.lifecycle.addressable("player-e1", OwnedObjectKind.PLAYER, f.world, 1, f.a0, true);
        InteractionTargetRef ref = f.index.require(f.world, 1);
        assertEquals(f.a, player.owner());
        assertEquals(0, player.incarnation());
        assertEquals(new OwnerEpoch(0), player.objectEpoch());
        assertEquals(player.objectId(), ref.objectId());
        assertEquals(player.incarnation(), ref.incarnation());
        assertThrows(IllegalStateException.class, () -> f.lifecycle.addressable("outside", OwnedObjectKind.PLAYER,
            f.world, 2, new RoutingKey(f.world, 20, 0), true));
        assertTrue(f.objects.current("outside").isEmpty());
    }

    @Test void liveLifecycleRemovalInvalidatesBeforeNumericIdReuseAndOldTicketRejectsE2() {
        Fixture f = new Fixture();
        ObjectOwnership player = f.lifecycle.addressable("player-e1", OwnedObjectKind.PLAYER, f.world, 1, f.a0, true);
        ObjectOwnership e1 = f.lifecycle.addressable("target-e1", OwnedObjectKind.ENTITY, f.world, 123, f.a1, true);
        InteractionTargetRef oldRef = f.index.require(f.world, 123);
        LiveInteractionAdmission.Ticket oldTicket = LiveInteractionAdmission.admit(f.directory, player, oldRef, f.index);
        f.lifecycle.removed("target-e1", f.world, 123);
        assertThrows(InteractionTargetIndex.UnknownInteractionTargetException.class, () -> f.index.require(f.world, 123));
        ObjectOwnership e2 = f.lifecycle.addressable("target-e2", OwnedObjectKind.ENTITY, f.world, 123, f.a1, true);
        InteractionTargetRef newRef = f.index.require(f.world, 123);
        assertNotEquals(oldRef.objectId(), newRef.objectId());
        assertNotEquals(e1.objectId(), e2.objectId());
        assertFalse(f.index.isCurrent(oldRef));
        assertThrows(IllegalStateException.class, () -> oldTicket.requireCurrent(f.directory, f.objects, f.index, f.a));
    }

    @Test void sameOwnerMoveRefreshesCandidateButCrossOwnerMoveInvalidatesItWithoutTransfer() {
        Fixture f = new Fixture();
        ObjectOwnership e1 = f.lifecycle.addressable("target-e1", OwnedObjectKind.ENTITY, f.world, 123, f.a0, true);
        f.lifecycle.routingObserved("target-e1", f.world, 123, f.a1);
        InteractionTargetRef moved = f.index.require(f.world, 123);
        assertEquals(f.a1, moved.routingKey());
        assertEquals(e1.incarnation(), moved.incarnation());
        f.lifecycle.routingObserved("target-e1", f.world, 123, f.b5);
        assertThrows(InteractionTargetIndex.UnknownInteractionTargetException.class, () -> f.index.require(f.world, 123));
        assertEquals(f.a, f.objects.current("target-e1").orElseThrow().owner());
    }

    private static final class Fixture {
        final WorldId world = new WorldId("minecraft:overworld");
        final RoutingKey a0 = new RoutingKey(world, 0, 0), a1 = new RoutingKey(world, 1, 0), b5 = new RoutingKey(world, 5, 0);
        final ExplicitLiveTopologyFixture.Plan plan = ExplicitLiveTopologyFixture.plan(world, a0,
            new TopologyGeneration(1), new OwnerEpoch(0), new OwnerEpoch(0));
        final RegionId a = plan.a();
        final RegionDirectory directory = new RegionDirectory();
        final ObjectOwnershipRegistry objects;
        final InteractionTargetIndex index = new InteractionTargetIndex();
        final EntityIndexLifecycleAdapter lifecycle;
        Fixture() {
            directory.publish(new TopologyGeneration(1), plan.routes(), Map.of());
            objects = new ObjectOwnershipRegistry(directory);
            lifecycle = new EntityIndexLifecycleAdapter(objects, directory, index);
        }
    }
}
