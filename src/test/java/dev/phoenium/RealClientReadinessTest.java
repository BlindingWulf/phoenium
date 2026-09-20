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
import dev.phoenium.topology.ClosureEscapeException;
import dev.phoenium.topology.FrozenRegionAuthority;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic real-client readiness tests; these construct no Minecraft client or packet. */
class RealClientReadinessTest {
    @Test void chunkSupportCoversSpawnDependenciesWithoutRemovingThePacketBoundary() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        int dependencyRadius = net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID
                .getStepTo(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
                .getAccumulatedRadiusOf(net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY);
        assertTrue(5 + 5 + 1 + dependencyRadius <= ExplicitLiveTopologyFixture.CHUNK_SUPPORT_RADIUS,
                "fixture must enclose spawn search, client view, and FULL generation dependencies");
        WorldId world = new WorldId("minecraft:overworld");
        var generation = new TopologyGeneration(1);
        var plan = ExplicitLiveTopologyFixture.plan(world, new RoutingKey(world, 0, 0),
                generation, new OwnerEpoch(0), new OwnerEpoch(0), new RoutingKey(world, 160, -160));
        var directory = new RegionDirectory();
        directory.publish(generation, plan.routes());
        for (int x : new int[] {138, 182}) for (int z : new int[] {-182, -138})
            assertEquals(plan.a(), directory.resolve(new RoutingKey(world, x, z)).orElseThrow().region());
        assertEquals(plan.a(), directory.resolve(new RoutingKey(world, 4, 0)).orElseThrow().region());
        assertEquals(plan.b(), directory.resolve(new RoutingKey(world, 5, 0)).orElseThrow().region());
        assertTrue(directory.resolve(new RoutingKey(world, 193, 0)).isEmpty());
        assertTrue(directory.snapshot().worldDefaults().isEmpty());
    }
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

    @Test void defaultOnlyWorldFreezesAsWholeWorldAuthority() {
        WorldId world = new WorldId("minecraft:overworld");
        RegionId serial = new RegionId(world, "stage2-live-world");
        TopologyGeneration generation = new TopologyGeneration(1);
        RegionDirectory directory = new RegionDirectory();
        directory.publish(generation, Map.of(), Map.of(world,
            new RegionRoute(serial, generation, new OwnerEpoch(0))));

        FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, serial);

        assertTrue(authority.wholeWorld());
        assertTrue(authority.domain().isEmpty(), "whole-world authority does not enumerate chunk keys");
        authority.requireWithin(Set.of(new RoutingKey(world, -1000, 2000)));
        authority.requireWithinBox(world, -1000, 1000, -2000, 2000);
        assertThrows(ClosureEscapeException.class,
            () -> authority.requireWithin(Set.of(new RoutingKey(new WorldId("minecraft:the_nether"), 0, 0))));
    }

    @Test void explicitRoutesDisableWholeWorldDefaultAuthority() {
        WorldId world = new WorldId("minecraft:overworld");
        RegionId serial = new RegionId(world, "stage2-live-world");
        TopologyGeneration generation = new TopologyGeneration(1);
        RoutingKey explicit = new RoutingKey(world, 0, 0);
        RegionRoute route = new RegionRoute(serial, generation, new OwnerEpoch(0));
        RegionDirectory directory = new RegionDirectory();
        directory.publish(generation, Map.of(explicit, route), Map.of(world, route));

        FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, serial);

        assertFalse(authority.wholeWorld());
        authority.requireWithin(Set.of(explicit));
        assertThrows(ClosureEscapeException.class,
            () -> authority.requireWithin(Set.of(new RoutingKey(world, 1, 0))));
        assertThrows(ClosureEscapeException.class,
            () -> authority.requireWithinBox(world, 0, 1, 0, 0));
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
