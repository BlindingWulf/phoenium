package dev.phoenium.live;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.interaction.InteractionTargetIndex;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EntityRelocationBoundaryTest {
    @Test void crossOwnerObservationInvalidatesUntilQuiescentCommitThenBumpsExactlyOnceAndEmitsOnce() {
        Fixture f = new Fixture();
        var before = f.adapter.addressable("entity", OwnedObjectKind.ENTITY, f.world, 7, f.a0, true);
        f.adapter.routingObserved("entity", f.world, 7, f.b0);
        assertEquals(1, f.adapter.pendingRelocationCount());
        assertThrows(InteractionTargetIndex.UnknownInteractionTargetException.class, () -> f.index.require(f.world, 7));
        assertEquals(f.a, f.objects.current("entity").orElseThrow().owner());

        assertEquals(1, f.adapter.advancePendingRelocations(f.runtimes));
        var after = f.objects.current("entity").orElseThrow();
        assertEquals(f.b, after.owner());
        assertEquals(before.objectEpoch().next(), after.objectEpoch());
        assertEquals(f.b0, f.index.require(f.world, 7).routingKey());
        assertEquals(List.of(new EntityIndexLifecycleAdapter.RelocationFact(f.a, f.b, "entity", before.incarnation())), f.facts);
        assertEquals(0, f.adapter.pendingRelocationCount());
        assertEquals(0, f.adapter.advancePendingRelocations(f.runtimes));
        assertEquals(1, f.facts.size(), "a settled fact cannot emit twice");
    }

    @Test void activeSourceOrDestinationLeaseDefersWithoutWaitingAndRetriesAfterRelease() {
        Fixture sourceBusy = new Fixture();
        sourceBusy.adapter.addressable("source-busy", OwnedObjectKind.ENTITY, sourceBusy.world, 8, sourceBusy.a0, true);
        sourceBusy.adapter.routingObserved("source-busy", sourceBusy.world, 8, sourceBusy.b0);
        try (var held = sourceBusy.ra.leases().acquire()) {
            assertEquals(0, sourceBusy.adapter.advancePendingRelocations(sourceBusy.runtimes));
            assertEquals(1, sourceBusy.adapter.pendingRelocationCount());
        }
        assertEquals(1, sourceBusy.adapter.advancePendingRelocations(sourceBusy.runtimes));

        Fixture destinationBusy = new Fixture();
        destinationBusy.adapter.addressable("destination-busy", OwnedObjectKind.ENTITY, destinationBusy.world, 9, destinationBusy.a0, true);
        destinationBusy.adapter.routingObserved("destination-busy", destinationBusy.world, 9, destinationBusy.b0);
        try (var held = destinationBusy.rb.leases().acquire()) {
            assertEquals(0, destinationBusy.adapter.advancePendingRelocations(destinationBusy.runtimes));
            assertEquals(1, destinationBusy.adapter.pendingRelocationCount());
        }
        assertEquals(1, destinationBusy.adapter.advancePendingRelocations(destinationBusy.runtimes));
    }

    @Test void bothRegionSelectionFencesRemainHeldThroughRelocationFactEmission() {
        Fixture f = new Fixture();
        List<EntityIndexLifecycleAdapter.RelocationFact> emitted = new ArrayList<>();
        var fenced = new EntityIndexLifecycleAdapter(f.objects, f.directory, f.index, fact -> {
            assertTrue(f.ra.leases().hasClaimOrSelection(), "source selection must fence publication through accounting");
            assertTrue(f.rb.leases().hasClaimOrSelection(), "destination selection must fence publication through accounting");
            emitted.add(fact);
        });
        var before = fenced.addressable("fenced", OwnedObjectKind.ENTITY, f.world, 18, f.a0, true);
        fenced.routingObserved("fenced", f.world, 18, f.b0);

        assertEquals(1, fenced.advancePendingRelocations(f.runtimes));
        assertEquals(List.of(new EntityIndexLifecycleAdapter.RelocationFact(f.a, f.b, "fenced", before.incarnation())), emitted);
        assertFalse(f.ra.leases().hasClaimOrSelection());
        assertFalse(f.rb.leases().hasClaimOrSelection());
    }

    @Test void newerObservationRemovalAndPlayerMovementInvalidateOrBypassPendingFact() {
        Fixture f = new Fixture();
        var first = f.adapter.addressable("entity", OwnedObjectKind.ENTITY, f.world, 10, f.a0, true);
        f.adapter.routingObserved("entity", f.world, 10, f.b0);
        assertEquals(1, f.adapter.pendingRelocationCount());
        f.adapter.routingObserved("entity", f.world, 10, f.a1);
        assertEquals(0, f.adapter.pendingRelocationCount(), "newer same-owner observation supersedes foreign destination");
        assertEquals(first.objectEpoch(), f.objects.current("entity").orElseThrow().objectEpoch());
        assertEquals(f.a1, f.index.require(f.world, 10).routingKey());

        f.adapter.routingObserved("entity", f.world, 10, f.b0);
        f.adapter.removed("entity", f.world, 10);
        assertEquals(0, f.adapter.pendingRelocationCount());
        assertEquals(0, f.adapter.advancePendingRelocations(f.runtimes));

        f.adapter.addressable("player", OwnedObjectKind.PLAYER, f.world, 11, f.a0, true);
        f.adapter.routingObserved("player", f.world, 11, f.b0);
        assertEquals(0, f.adapter.pendingRelocationCount(), "player packet movement owns a separate protocol");
    }

    @Test void aToBToAProducesOrderedFactsAndNeverReusesObjectEpoch() {
        Fixture f = new Fixture();
        f.adapter.addressable("boomerang", OwnedObjectKind.ENTITY, f.world, 12, f.a0, true);
        f.adapter.routingObserved("boomerang", f.world, 12, f.b0);
        assertEquals(1, f.adapter.advancePendingRelocations(f.runtimes));
        assertEquals(1, f.objects.current("boomerang").orElseThrow().objectEpoch().value());

        f.adapter.routingObserved("boomerang", f.world, 12, f.a0);
        assertEquals(1, f.adapter.advancePendingRelocations(f.runtimes));
        assertEquals(2, f.objects.current("boomerang").orElseThrow().objectEpoch().value());
        assertEquals(List.of(
            new EntityIndexLifecycleAdapter.RelocationFact(f.a, f.b, "boomerang", 0),
            new EntityIndexLifecycleAdapter.RelocationFact(f.b, f.a, "boomerang", 0)), f.facts);
    }

    @Test void staleEpochOrIncarnationCannotCommitAndPublicationIsFailClosedUntilCommit() {
        Fixture f = new Fixture();
        f.adapter.addressable("stale", OwnedObjectKind.ENTITY, f.world, 13, f.a0, true);
        f.adapter.routingObserved("stale", f.world, 13, f.b0);
        assertThrows(InteractionTargetIndex.UnknownInteractionTargetException.class, () -> f.index.require(f.world, 13));

        // Simulate another already-authorized publication winning before this pending observation.
        var moved = f.objects.transitionOwner("stale", f.b1, new OwnerEpoch(0));
        assertEquals(1, moved.objectEpoch().value());
        assertEquals(0, f.adapter.advancePendingRelocations(f.runtimes));
        assertEquals(0, f.adapter.pendingRelocationCount());
        assertTrue(f.facts.isEmpty());

        f.adapter.removed("stale", f.world, 13);
        var replacement = f.adapter.addressable("stale", OwnedObjectKind.ENTITY, f.world, 13, f.b1, true);
        assertEquals(1, replacement.incarnation());
        assertEquals(0, f.adapter.pendingRelocationCount());
    }

    @Test void pendingFactSurvivesTemporaryTopologyLossAndCommitsAgainstRepublishedDestination() {
        Fixture f = new Fixture();
        var before = f.adapter.addressable("topology", OwnedObjectKind.ENTITY, f.world, 14, f.a0, true);
        f.adapter.routingObserved("topology", f.world, 14, f.b0);

        TopologyGeneration missingGeneration = f.generation.next();
        f.directory.publish(missingGeneration, Map.of(
            f.a0, new RegionRoute(f.a, missingGeneration, new OwnerEpoch(0)),
            f.a1, new RegionRoute(f.a, missingGeneration, new OwnerEpoch(0)),
            f.b1, new RegionRoute(f.b, missingGeneration, new OwnerEpoch(0))), Map.of());
        assertEquals(0, f.adapter.advancePendingRelocations(f.runtimes));
        assertEquals(1, f.adapter.pendingRelocationCount(), "temporary route loss must not strand the physical observation");
        assertEquals(f.a, f.objects.current("topology").orElseThrow().owner());
        assertThrows(InteractionTargetIndex.UnknownInteractionTargetException.class, () -> f.index.require(f.world, 14));

        TopologyGeneration restoredGeneration = missingGeneration.next();
        f.directory.publish(restoredGeneration, Map.of(
            f.a0, new RegionRoute(f.a, restoredGeneration, new OwnerEpoch(0)),
            f.a1, new RegionRoute(f.a, restoredGeneration, new OwnerEpoch(0)),
            f.b0, new RegionRoute(f.b, restoredGeneration, new OwnerEpoch(0)),
            f.b1, new RegionRoute(f.b, restoredGeneration, new OwnerEpoch(0))), Map.of());
        assertEquals(1, f.adapter.advancePendingRelocations(f.runtimes));
        var after = f.objects.current("topology").orElseThrow();
        assertEquals(f.b, after.owner());
        assertEquals(restoredGeneration, after.topologyGeneration());
        assertEquals(before.objectEpoch().next(), after.objectEpoch());
        assertEquals(0, f.adapter.pendingRelocationCount());
        assertEquals(1, f.facts.size());
    }

    @Test void firstObservationIntoUnresolvedKeyRemainsPendingUntilTopologyPublishesIt() {
        Fixture f = new Fixture();
        RoutingKey future = new RoutingKey(f.world, 17, 0);
        var before = f.adapter.addressable("future-route", OwnedObjectKind.ENTITY, f.world, 17, f.a0, true);

        f.adapter.routingObserved("future-route", f.world, 17, future);
        assertEquals(1, f.adapter.pendingRelocationCount());
        assertEquals(0, f.adapter.advancePendingRelocations(f.runtimes));
        assertEquals(f.a, f.objects.current("future-route").orElseThrow().owner());
        assertThrows(InteractionTargetIndex.UnknownInteractionTargetException.class, () -> f.index.require(f.world, 17));

        TopologyGeneration published = f.generation.next();
        f.directory.publish(published, Map.of(
            f.a0, new RegionRoute(f.a, published, new OwnerEpoch(0)),
            f.a1, new RegionRoute(f.a, published, new OwnerEpoch(0)),
            f.b0, new RegionRoute(f.b, published, new OwnerEpoch(0)),
            f.b1, new RegionRoute(f.b, published, new OwnerEpoch(0)),
            future, new RegionRoute(f.b, published, new OwnerEpoch(0))), Map.of());

        assertEquals(1, f.adapter.advancePendingRelocations(f.runtimes));
        var after = f.objects.current("future-route").orElseThrow();
        assertEquals(f.b, after.owner());
        assertEquals(before.objectEpoch().next(), after.objectEpoch());
        assertEquals(future, f.index.require(f.world, 17).routingKey());
        assertEquals(0, f.adapter.pendingRelocationCount());
        assertEquals(List.of(new EntityIndexLifecycleAdapter.RelocationFact(f.a, f.b, "future-route", 0)), f.facts);
    }

    @Test void sinkFailureOccursAfterSingleCommitAndCannotReplayAuthorityTransition() {
        Fixture f = new Fixture();
        var failing = new EntityIndexLifecycleAdapter(f.objects, f.directory, f.index, fact -> {
            throw new IllegalStateException("accounting sink failed");
        });
        var before = failing.addressable("sink-failure", OwnedObjectKind.ENTITY, f.world, 15, f.a0, true);
        failing.routingObserved("sink-failure", f.world, 15, f.b0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> failing.advancePendingRelocations(f.runtimes));
        assertEquals("accounting sink failed", failure.getMessage());
        var committed = f.objects.current("sink-failure").orElseThrow();
        assertEquals(f.b, committed.owner());
        assertEquals(before.objectEpoch().next(), committed.objectEpoch());
        assertEquals(f.b0, f.index.require(f.world, 15).routingKey());
        assertEquals(0, failing.pendingRelocationCount(), "post-commit failure must never leave a replayable relocation fact");
        assertEquals(0, failing.advancePendingRelocations(f.runtimes));
        assertEquals(committed.objectEpoch(), f.objects.current("sink-failure").orElseThrow().objectEpoch());
    }

    @Test void closeIntentionallyRetiresPendingFactsWithoutPublishingOrAccounting() {
        Fixture f = new Fixture();
        f.adapter.addressable("shutdown", OwnedObjectKind.ENTITY, f.world, 16, f.a0, true);
        f.adapter.routingObserved("shutdown", f.world, 16, f.b0);
        assertEquals(1, f.adapter.pendingRelocationCount());
        f.adapter.close();
        assertEquals(0, f.adapter.pendingRelocationCount());
        assertTrue(f.facts.isEmpty());
        assertTrue(f.objects.current("shutdown").isEmpty());
    }

    private static final class Fixture {
        final WorldId world = new WorldId("relocation");
        final RegionId a = new RegionId(world, "a"), b = new RegionId(world, "b");
        final RoutingKey a0 = new RoutingKey(world, 0, 0), a1 = new RoutingKey(world, 1, 0);
        final RoutingKey b0 = new RoutingKey(world, 8, 0), b1 = new RoutingKey(world, 9, 0);
        final TopologyGeneration generation = new TopologyGeneration(1);
        final RegionDirectory directory = new RegionDirectory();
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final InteractionTargetIndex index = new InteractionTargetIndex();
        final List<EntityIndexLifecycleAdapter.RelocationFact> facts = new ArrayList<>();
        final EntityIndexLifecycleAdapter adapter = new EntityIndexLifecycleAdapter(objects, directory, index, facts::add);
        final RegionRuntime ra = ready(a), rb = ready(b);
        final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>();
        Fixture() {
            directory.publish(generation, Map.of(
                a0, new RegionRoute(a, generation, new OwnerEpoch(0)),
                a1, new RegionRoute(a, generation, new OwnerEpoch(0)),
                b0, new RegionRoute(b, generation, new OwnerEpoch(0)),
                b1, new RegionRoute(b, generation, new OwnerEpoch(0))), Map.of());
            runtimes.put(a, ra); runtimes.put(b, rb);
        }
        private static RegionRuntime ready(RegionId id) {
            RegionRuntime runtime = new RegionRuntime(id, new OwnerEpoch(0), false);
            runtime.transitionTo(RegionLifecycle.READY);
            return runtime;
        }
    }
}
