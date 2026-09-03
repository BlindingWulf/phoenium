package dev.phoenium;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.ObjectOperationExecutor;
import dev.phoenium.ownership.ObjectOwnerGateway;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectEnvelope;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLease;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.scheduler.SchedulerLifecycle;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.topology.ClosureEscapeException;
import dev.phoenium.topology.FrozenRegionAuthority;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyCoordinator;
import dev.phoenium.topology.TopologyPlanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The Stage 3 movement cases M-01..M-06 requested in
 * docs/STAGE3_MOVEMENT_ARCHITECTURE_REVIEW.md, qualified as "Stage 3 movement M-xx" to keep them
 * distinct from the compatibility identifiers in TEST_MATRIX.md, plus the deterministic proof of
 * the quiescent publication window. Every case uses explicit scheduler steps, manual leases and
 * controlled admission; none uses elapsed time, sleep, a latch race, or a live client.
 *
 * The executor seam is the frozen-authority guard wiring required by M-02 and M-06: the complete
 * footprint of a requested sweep (path box with padding plus explicit callback/query cells) is
 * checked against the executing region's frozen authority BEFORE the first directory lookup of a
 * footprint key and before any position store. An escaping footprint raises
 * ClosureEscapeException, which the scheduler converts into the hard failure barrier; it is never
 * clamped, faked, replayed, or satisfied by a destination-only check.
 */
@Timeout(value = 60, unit = SECONDS)
class Stage3MovementTest {

    /** Stage 3 movement M-01: two adjacent keys under A (one negative); the move keeps RegionId and object epoch, and queued identity work stays valid. */
    @Test
    @DisplayName("Stage 3 movement M-01: same-owner adjacent key move keeps identity, epoch, and queued work valid")
    void m01SameOwnerAdjacentKeyMoveKeepsRegionIdObjectEpochAndQueuedWork() {
        MovementHarness h = new MovementHarness();
        RoutingKey negativeKey = new RoutingKey(h.world, -1, 0);
        assertTrue(h.topology(Map.of(negativeKey, h.aId, h.aKey, h.aId, h.bKey, h.bId)));
        ObjectOwnership entity = h.objects.admit("m01-entity", OwnedObjectKind.ENTITY, negativeKey);
        assertEquals(h.aId, entity.owner());
        assertEquals(0, entity.objectEpoch().value());
        // The sweep is queued first so it executes before the identity-bound task.
        h.requestSweep("m01-entity", new TaskCorrelationId(1), List.of(h.aKey), 0, Set.of());
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            h.objectsGateway.submit(h.envelope("m01-entity", new OwnerEpoch(0), "movement", AdmissionPolicy.REJECT, 1)));
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            h.objectsGateway.submit(h.envelope("m01-entity", new OwnerEpoch(0), "entity-mutation", AdmissionPolicy.REJECT, 2)));
        h.scheduler.advanceOne(); // the sweep executes: the actual key changes under A
        h.scheduler.advanceOne(); // the identity-bound task queued before the move still executes
        ObjectOwnership after = h.objects.current("m01-entity").orElseThrow();
        assertEquals(h.aKey, after.routingKey(), "the actual key changed");
        assertEquals(h.aId, after.owner(), "the RegionId stayed fixed");
        assertEquals(0, after.objectEpoch().value(), "the object epoch stayed fixed");
        assertEquals(0, after.incarnation(), "no transfer record: no re-admission or owner transition");
        assertEquals(h.aKey, h.positionState.get("m01-entity"));
        assertEquals(2, h.aWrites, "both operations applied in A");
        assertEquals(0, h.bWrites);
        assertEquals(1, h.entityMutations, "the queued identity work remained valid");
        assertEquals(h.aId, h.lastMutationRegion);
        assertFalse(h.barrier.tripped());
    }

    /** Stage 3 movement M-02: the full-footprint guard fires before the first foreign lookup; a destination-only guard demonstrably cannot see the escape; B's counters stay zero. */
    @Test
    @DisplayName("Stage 3 movement M-02: full-footprint guard fires before first foreign lookup; destination-only guard fails")
    void m02FrozenAuthorityFullFootprintGuardFiresBeforeFirstForeignLookup() {
        MovementHarness h = new MovementHarness();
        publishSquare(h, 1); // A's frozen domain is exactly the 3x3 square around (0,0)
        h.objects.admit("m02-entity", OwnedObjectKind.ENTITY, h.aKey);
        RoutingKey destination = new RoutingKey(h.world, 1, 0); // the resolved endpoint sits inside A
        h.requestSweep("m02-entity", new TaskCorrelationId(1), List.of(destination), 1, Set.of()); // padding crosses A's edge
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            h.objectsGateway.submit(h.envelope("m02-entity", new OwnerEpoch(0), "movement", AdmissionPolicy.REJECT, 1)));
        h.scheduler.advanceOne();
        FailureBarrier.FailureRecord record = h.barrier.record().orElseThrow();
        assertInstanceOf(ClosureEscapeException.class, record.cause());
        assertEquals(SchedulerLifecycle.FAILED, h.scheduler.lifecycle());
        assertEquals(0, h.bReads, "no foreign lookup happened");
        assertEquals(0, h.bWrites, "no foreign write happened");
        assertEquals(0, h.aWrites, "no position store happened");
        assertNull(h.positionState.get("m02-entity"), "the position was never stored");
        long advances = h.scheduler.advances();
        h.scheduler.advanceOne();
        assertEquals(advances, h.scheduler.advances(), "no replay after the hard barrier");
        // A destination-only guard cannot see this escape: the endpoint alone is inside the domain.
        FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(h.directory, h.aId);
        assertDoesNotThrow(() -> authority.requireWithin(Set.of(destination)),
            "a destination-only check passes, so only the full-footprint guard catches the escape");
        assertThrows(ClosureEscapeException.class, () -> authority.requireWithinBox(h.world, -1, 2, -1, 1),
            "the full swept box with padding escapes the frozen authority");
    }

    /** Stage 3 movement M-02 control: a sweep whose complete footprint stays inside the frozen authority executes normally. */
    @Test
    @DisplayName("Stage 3 movement M-02 control: sweep fully inside frozen authority executes")
    void m02ControlSweepEntirelyWithinFrozenAuthorityExecutes() {
        MovementHarness h = new MovementHarness();
        publishSquare(h, 1);
        h.objects.admit("m02-control", OwnedObjectKind.ENTITY, h.aKey);
        h.requestSweep("m02-control", new TaskCorrelationId(1), List.of(new RoutingKey(h.world, 1, 0)), 0, Set.of());
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            h.objectsGateway.submit(h.envelope("m02-control", new OwnerEpoch(0), "movement", AdmissionPolicy.REJECT, 1)));
        h.scheduler.advanceOne();
        assertFalse(h.barrier.tripped());
        assertEquals(1, h.aWrites);
        assertEquals(0, h.bWrites);
        assertEquals(new RoutingKey(h.world, 1, 0), h.positionState.get("m02-control"));
        assertEquals(0, h.objects.current("m02-control").orElseThrow().objectEpoch().value());
    }

    /** Stage 3 movement M-03: conflicting closures record pending convergence; old domains never expand, the bridge is not ordinary work, no overlapping successor lease, and the first releaser cannot resume before convergence. */
    @Test
    @DisplayName("Stage 3 movement M-03: conflicting closures record pending convergence with frozen old domains")
    void m03ConflictingClosuresRecordPendingConvergenceKeepingOldDomainsFrozen() {
        MovementHarness h = new MovementHarness();
        TopologyPlanner planner = new TopologyPlanner();
        RoutingKey bridge = new RoutingKey(h.world, 5, 0);
        Set<RoutingKey> aClosure = planner.requiredClosure(Set.of(h.aKey), 5);
        Set<RoutingKey> bClosure = planner.requiredClosure(Set.of(h.bKey), 5);
        Set<RoutingKey> conflicted = new LinkedHashSet<>(aClosure);
        conflicted.retainAll(bClosure);
        assertTrue(conflicted.contains(bridge));
        assertTrue(conflicted.size() > 1, "the two radius-5 closures conflict over a whole strip of keys");

        TopologyCoordinator coordinator = h.coordinator();
        RegionLease aLease = h.a.leases().acquire();
        RegionLease bLease = h.b.leases().acquire();
        try {
            Map<RoutingKey, RegionId> converged = new LinkedHashMap<>();
            converged.put(h.aKey, h.aId);
            converged.put(h.bKey, h.bId);
            conflicted.forEach(key -> converged.put(key, h.bId));
            coordinator.recordConvergence(conflicted,
                new TopologyCoordinator.Transition(Set.of(h.aId, h.bId), converged), "conflicting radius-5 closures");
            assertTrue(coordinator.hasPendingConvergence());
            assertEquals(conflicted, coordinator.convergence().conflictedKeys());

            // The old domains never expand: the published directory stays frozen at generation 1.
            assertEquals(1, h.directory.snapshot().generation().value());
            assertEquals(Set.of(h.aKey, h.bKey), h.directory.snapshot().routes().keySet());

            // The bridge cannot apply as ordinary work: while convergence is pending it has no route.
            RegionTaskEnvelope bridgeWork = new RegionTaskEnvelope("bridge-holder", new TaskPayload.Append("bridge"), bridge,
                new TopologyGeneration(1), new OwnerEpoch(0), "convergence-test",
                new TaskCorrelationId(1), new TaskSequence(1), AdmissionPolicy.REROUTE);
            assertThrows(IllegalArgumentException.class, () -> h.gateway.submit(bridgeWork));

            // Conflicting transition requests are denied while convergence is pending.
            assertThrows(IllegalStateException.class,
                () -> coordinator.request(new TopologyCoordinator.Transition(Set.of(h.aId, h.bId),
                    Map.of(h.aKey, h.aId, h.bKey, h.bId))));

            // No overlapping successor lease: the commit waits for both participants' old leases,
            // and no successor epoch is published in the meantime.
            assertFalse(coordinator.advance());
            assertEquals(new OwnerEpoch(0), h.a.ownerEpoch());
            assertEquals(new OwnerEpoch(0), h.b.ownerEpoch());

            // The first releaser cannot resume before convergence completes.
            aLease.close();
            assertTrue(h.a.transitioning());
            assertEquals(OwnerGateway.Delivery.DEFERRED,
                h.gateway.submit(h.fixture.task(h.aKey, new TaskPayload.Append("resume"), AdmissionPolicy.REROUTE, 2)));
            h.scheduler.advanceOne();
            assertTrue(h.a.immutableLog().isEmpty(), "A stays closed while convergence is pending");
            assertFalse(coordinator.advance(), "B's old lease still blocks the commit");

            // Only after the last old lease releases does the converged proposal commit.
            bLease.close();
            assertTrue(coordinator.advance());
            assertEquals(2, h.directory.snapshot().generation().value());
            assertEquals(h.bId, h.directory.resolve(bridge).orElseThrow().region());
            assertFalse(h.a.transitioning());
            assertFalse(h.b.transitioning());

            // The bridge becomes admissible only through the converged publication, and A's
            // deferred work resumes only now.
            assertEquals(OwnerGateway.Delivery.REROUTED, h.gateway.submit(bridgeWork));
            h.scheduler.advanceOne();
            h.scheduler.advanceOne();
            assertEquals(List.of("resume"), h.a.immutableLog());
            assertEquals(List.of("bridge"), h.b.immutableLog());
        } finally {
            if (h.a.leases().hasActiveLease()) aLease.close();
            if (h.b.leases().hasActiveLease()) bLease.close();
        }
    }

    /** Stage 3 movement M-04: exactly one object-epoch increment per real owner change; a lease-blocked commit waits by continuation; no ABA reuse; reads and unrelated generations bump nothing; split survivors and merged arrivals proven separately. */
    @Test
    @DisplayName("Stage 3 movement M-04: one epoch increment per real owner change, no ABA")
    void m04ExactlyOneObjectEpochIncrementPerRealOwnerChangeAndNoAba() {
        // (1) A lease-blocked commit waits by continuation and applies exactly once.
        MovementHarness h = new MovementHarness();
        h.objects.admit("m04-object", OwnedObjectKind.ENTITY, h.aKey);
        TopologyCoordinator coordinator = h.coordinator();
        try (RegionLease lease = h.a.leases().acquire();
             OwnershipContext.Scope ignored = OwnershipContext.enter(h.aId, lease.epoch(), OperationCategory.OWNER_TASK)) {
            coordinator.request(new TopologyCoordinator.Transition(Set.of(h.aId, h.bId), Map.of(h.aKey, h.bId, h.bKey, h.bId)));
            assertFalse(coordinator.advance(), "the active A lease blocks the commit");
            assertTrue(coordinator.hasPendingTransition());
        }
        assertTrue(coordinator.advance(), "the commit resumes by continuation after the lease releases");
        ObjectOwnership afterFirst = h.objects.current("m04-object").orElseThrow();
        assertEquals(h.bId, afterFirst.owner());
        assertEquals(1, afterFirst.objectEpoch().value(), "exactly one increment for the real A -> B change");
        assertEquals(new OwnerEpoch(1), h.b.ownerEpoch());

        // (3) Repeated reads and an unrelated same-owner generation publish bump nothing
        // (the exact counterexample from the review's inspection witness table).
        for (int i = 0; i < 5; i++) {
            assertEquals(1, h.objects.current("m04-object").orElseThrow().objectEpoch().value());
        }
        TopologyGeneration unrelated = h.directory.snapshot().generation().next();
        h.directory.publish(unrelated, Map.of(
            h.aKey, new RegionRoute(h.bId, unrelated, h.b.ownerEpoch()),
            h.bKey, new RegionRoute(h.bId, unrelated, h.b.ownerEpoch())));
        ObjectOwnership afterUnrelated = h.objects.current("m04-object").orElseThrow();
        assertEquals(unrelated, afterUnrelated.topologyGeneration());
        assertEquals(1, afterUnrelated.objectEpoch().value(), "an unrelated generation bumps nothing");

        // (2) No ABA: A -> B -> A with no intervening object read must not reuse epoch 0.
        MovementHarness aba = new MovementHarness();
        aba.objects.admit("aba-object", OwnedObjectKind.ENTITY, aba.aKey);
        assertTrue(aba.topology(Map.of(aba.aKey, aba.bId, aba.bKey, aba.bId)));
        assertTrue(aba.topology(Map.of(aba.aKey, aba.aId, aba.bKey, aba.bId)));
        ObjectOwnership abaAfter = aba.objects.current("aba-object").orElseThrow();
        assertEquals(aba.aId, abaAfter.owner());
        assertEquals(2, abaAfter.objectEpoch().value(), "the owner returned to A, but epoch 0 is never reused");
        assertThrows(IllegalStateException.class,
            () -> aba.objects.requireCurrentOwner("aba-object", new OwnerEpoch(0), aba.aId),
            "a caller holding epoch 0 cannot validate even though the owner is A again");
        assertEquals(ObjectOwnerGateway.Delivery.REJECTED_STALE,
            aba.objectsGateway.submit(aba.envelope("aba-object", new OwnerEpoch(0), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        assertEquals(ObjectOwnerGateway.Delivery.REROUTED,
            aba.objectsGateway.submit(aba.envelope("aba-object", new OwnerEpoch(0), "entity-mutation", AdmissionPolicy.REROUTE, 2)));
        aba.scheduler.advanceOne();
        assertEquals(1, aba.entityMutations, "the rerouted work executes with the current epoch");
        assertEquals(aba.aId, aba.lastMutationRegion);

        // (4) Merged arrival, tested separately: a key merged into B increments exactly once; the B resident does not.
        MovementHarness merged = new MovementHarness();
        merged.objects.admit("arrival", OwnedObjectKind.ENTITY, merged.aKey);
        merged.objects.admit("resident", OwnedObjectKind.ENTITY, merged.bKey);
        assertTrue(merged.topology(Map.of(merged.aKey, merged.bId, merged.bKey, merged.bId)));
        assertEquals(merged.bId, merged.objects.current("arrival").orElseThrow().owner());
        assertEquals(1, merged.objects.current("arrival").orElseThrow().objectEpoch().value(), "merged arrival: exactly one increment");
        assertEquals(0, merged.objects.current("resident").orElseThrow().objectEpoch().value(), "resident: unchanged");

        // (5) Split survivor, tested separately: the surviving part of the split keeps its epoch; the departing key increments exactly once.
        MovementHarness split = new MovementHarness();
        RoutingKey cKey = new RoutingKey(split.world, 11, 0);
        TopologyCoordinator seed = split.coordinator();
        seed.request(new TopologyCoordinator.Transition(Set.of(split.aId, split.bId),
            Map.of(split.aKey, split.aId, split.bKey, split.bId, cKey, split.bId)));
        assertTrue(seed.advance());
        split.objects.admit("split-survivor", OwnedObjectKind.ENTITY, split.bKey);
        split.objects.admit("departing", OwnedObjectKind.ENTITY, cKey);
        assertTrue(split.topology(Map.of(split.aKey, split.aId, split.bKey, split.bId, cKey, split.aId)));
        assertEquals(split.bId, split.objects.current("split-survivor").orElseThrow().owner());
        assertEquals(0, split.objects.current("split-survivor").orElseThrow().objectEpoch().value(), "split survivor: unchanged");
        assertEquals(split.aId, split.objects.current("departing").orElseThrow().owner());
        assertEquals(1, split.objects.current("departing").orElseThrow().objectEpoch().value(), "departing key: exactly one increment");
    }

    /** Stage 3 movement M-05: stale inbox work (object and generic location payloads, REJECT and REROUTE) never mutates the old owner; an object task follows the current identity even when its old chunk remains in A. */
    @Test
    @DisplayName("Stage 3 movement M-05: stale inbox work rejects or reroutes; object follows current identity")
    void m05StaleInboxWorkRejectsOrReroutesAndObjectFollowsCurrentIdentity() {
        // (a) Generic location payload, REROUTE: enqueued into A before commit, delivered after.
        MovementHarness locReroute = new MovementHarness();
        assertEquals(OwnerGateway.Delivery.QUEUED,
            locReroute.gateway.submit(locReroute.fixture.task(locReroute.aKey, new TaskPayload.Append("loc-reroute"), AdmissionPolicy.REROUTE, 1)));
        assertEquals(1, locReroute.a.inboxSize());
        assertTrue(locReroute.topology(Map.of(locReroute.aKey, locReroute.bId, locReroute.bKey, locReroute.bId)));
        locReroute.scheduler.advanceOne(); // A polls, revalidation reroutes through the current directory
        locReroute.scheduler.advanceOne(); // B applies
        assertTrue(locReroute.a.immutableLog().isEmpty(), "the old owner never mutates");
        assertEquals(List.of("loc-reroute"), locReroute.b.immutableLog());

        // (b) Generic location payload, REJECT: dropped at execution revalidation.
        MovementHarness locReject = new MovementHarness();
        assertEquals(OwnerGateway.Delivery.QUEUED,
            locReject.gateway.submit(locReject.fixture.task(locReject.aKey, new TaskPayload.Append("loc-reject"), AdmissionPolicy.REJECT, 1)));
        assertTrue(locReject.topology(Map.of(locReject.aKey, locReject.bId, locReject.bKey, locReject.bId)));
        locReject.scheduler.advanceOne();
        assertTrue(locReject.a.immutableLog().isEmpty());
        assertTrue(locReject.b.immutableLog().isEmpty());
        assertEquals(0, locReject.a.inboxSize());
        assertEquals(0, locReject.b.inboxSize());

        // (c) Object payload, REJECT: the stale epoch is dropped; the old owner never mutates.
        MovementHarness objReject = new MovementHarness();
        ObjectOwnership rejected = objReject.objects.admit("obj-reject", OwnedObjectKind.ENTITY, objReject.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            objReject.objectsGateway.submit(objReject.envelope("obj-reject", rejected.objectEpoch(), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        assertTrue(objReject.topology(Map.of(objReject.aKey, objReject.bId, objReject.bKey, objReject.bId)));
        objReject.scheduler.advanceOne();
        assertEquals(0, objReject.entityMutations);
        assertEquals(0, objReject.a.inboxSize());
        assertEquals(0, objReject.b.inboxSize());

        // (d) Object payload, REROUTE: rerouted exactly once and executed by the new owner with the new epoch.
        MovementHarness objReroute = new MovementHarness();
        ObjectOwnership rerouted = objReroute.objects.admit("obj-reroute", OwnedObjectKind.ENTITY, objReroute.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            objReroute.objectsGateway.submit(objReroute.envelope("obj-reroute", rerouted.objectEpoch(), "entity-mutation", AdmissionPolicy.REROUTE, 1)));
        assertTrue(objReroute.topology(Map.of(objReroute.aKey, objReroute.bId, objReroute.bKey, objReroute.bId)));
        objReroute.scheduler.advanceOne(); // A's stale copy is rerouted into B's inbox
        assertEquals(0, objReroute.entityMutations);
        assertEquals(0, objReroute.a.inboxSize());
        assertEquals(1, objReroute.b.inboxSize());
        objReroute.scheduler.advanceOne();
        assertEquals(1, objReroute.entityMutations);
        assertEquals(objReroute.bId, objReroute.lastMutationRegion);
        objReroute.scheduler.advanceOne();
        assertEquals(1, objReroute.entityMutations, "no duplicate execution");
        assertEquals(objReroute.bId, objReroute.objects.current("obj-reroute").orElseThrow().owner());
        assertEquals(1, objReroute.objects.current("obj-reroute").orElseThrow().objectEpoch().value());

        // (e) The object follows its current identity even when its old chunk remains in A.
        MovementHarness follow = new MovementHarness();
        RoutingKey aKey2 = new RoutingKey(follow.world, 1, 0);
        assertTrue(follow.topology(Map.of(follow.aKey, follow.aId, aKey2, follow.aId, follow.bKey, follow.bId)));
        ObjectOwnership moved = follow.objects.admit("follow", OwnedObjectKind.ENTITY, follow.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            follow.objectsGateway.submit(follow.envelope("follow", moved.objectEpoch(), "entity-mutation", AdmissionPolicy.REROUTE, 1)));
        follow.objects.updateRoutingKey("follow", aKey2, moved.objectEpoch()); // the object moves under A
        assertTrue(follow.topology(Map.of(follow.aKey, follow.aId, aKey2, follow.bId, follow.bKey, follow.bId)));
        // The old chunk aKey remains in A, but the object's current key aKey2 is now routed to B.
        assertEquals(follow.aId, follow.directory.resolve(follow.aKey).orElseThrow().region());
        assertEquals(follow.bId, follow.directory.resolve(aKey2).orElseThrow().region());
        follow.scheduler.advanceOne(); // A's stale copy is rerouted to the object's current owner
        follow.scheduler.advanceOne();
        assertEquals(1, follow.entityMutations);
        assertEquals(follow.bId, follow.lastMutationRegion, "the work followed the object to B, not the old chunk in A");
        assertEquals(0, follow.a.inboxSize());
        assertEquals(0, follow.b.inboxSize());
        ObjectOwnership finalBinding = follow.objects.current("follow").orElseThrow();
        assertEquals(follow.bId, finalBinding.owner());
        assertEquals(aKey2, finalBinding.routingKey());
        assertEquals(1, finalBinding.objectEpoch().value());
    }

    /** Stage 3 movement M-06: adversarial footprints fail closed before any foreign access; never clamped, faked, or replayed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adversarialFootprints")
    @DisplayName("Stage 3 movement M-06: {0} fails closed before foreign access")
    void m06AdversarialFootprintsFailClosedBeforeForeignAccess(
            String name, List<RoutingKey> waypoints, int padding, Set<RoutingKey> extraFootprint) {
        MovementHarness h = new MovementHarness(); // fixture domain: A owns exactly {(0,0)}, B owns {(10,0)}
        h.objects.admit(name, OwnedObjectKind.ENTITY, h.aKey);
        h.requestSweep(name, new TaskCorrelationId(1), waypoints, padding, extraFootprint);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            h.objectsGateway.submit(h.envelope(name, new OwnerEpoch(0), "movement", AdmissionPolicy.REJECT, 1)));
        h.scheduler.advanceOne();
        FailureBarrier.FailureRecord record = h.barrier.record()
            .orElseThrow(() -> new AssertionError(name + " did not fail closed"));
        assertInstanceOf(ClosureEscapeException.class, record.cause(), name);
        assertEquals(SchedulerLifecycle.FAILED, h.scheduler.lifecycle());
        assertEquals(0, h.bReads, name + ": no foreign read");
        assertEquals(0, h.bWrites, name + ": no foreign write");
        assertEquals(0, h.aWrites, name + ": no position store before the guard");
        assertEquals(h.aKey, h.positionState.getOrDefault(name, h.aKey), name + ": no clamp or fake position");
        long advances = h.scheduler.advances();
        h.scheduler.advanceOne();
        assertEquals(advances, h.scheduler.advances(), name + ": no replay after the hard barrier");
    }

    private static Stream<Arguments> adversarialFootprints() {
        WorldId world = new WorldId("test");
        return Stream.of(
            // Huge finite delta: a box of ~2^20 cells cannot be contained in a one-key domain.
            Arguments.of("hugeFiniteDelta", List.of(new RoutingKey(world, 1 << 20, 0)), 1, Set.of()),
            // noPhysics teleport: the endpoint lies inside a foreign region; the path crosses unrouted keys.
            Arguments.of("noPhysicsTeleport", List.of(new RoutingKey(world, 10, 0)), 0, Set.of()),
            // Oversized box: in-domain endpoint with a 64-cell query margin.
            Arguments.of("oversizedBox", List.<RoutingKey>of(), 64, Set.of()),
            // Step height: the collision query reaches the column beside the domain.
            Arguments.of("stepHeight", List.<RoutingKey>of(), 0, Set.of(new RoutingKey(world, 0, 1))),
            // Projectile path: a range-6 projectile in +x crosses keys the region does not own.
            Arguments.of("projectilePath", List.of(new RoutingKey(world, 2, 0), new RoutingKey(world, 4, 0), new RoutingKey(world, 6, 0)), 0, Set.of()),
            // Repeated moves in one tick: three consecutive +x steps accumulate past the domain edge.
            Arguments.of("repeatedMovesInOneTick", List.of(new RoutingKey(world, 1, 0), new RoutingKey(world, 2, 0), new RoutingKey(world, 3, 0)), 0, Set.of()),
            // Callback outside D: the swept box is in-domain, but the callback reaches outside the domain.
            Arguments.of("callbackOutsideDomain", List.<RoutingKey>of(), 0, Set.of(new RoutingKey(world, 5, 0))));
    }

    /**
     * Publication window proof: inside the seam between directory.publish and reconcileTopology the
     * directory already names the new owner while no object has any owner (stale old-owner mutation
     * impossible, premature new-owner mutation impossible, dual authority impossible). The seam is a
     * synchronous non-blocking Runnable; no sleep, wait, or join exists in the coordinator.
     */
    @Test
    @DisplayName("Publication window: zero stale, premature, or dual authority between publish and reconcile")
    void publicationWindowProbeProvesZeroStalePrematureOrDualAuthority() throws Exception {
        MovementHarness h = new MovementHarness();
        h.objects.admit("pub-entity", OwnedObjectKind.ENTITY, h.aKey);
        TopologyCoordinator coordinator = h.coordinator();
        AtomicBoolean insideWindow = new AtomicBoolean(false);
        coordinator.setPublicationWindowProbe(() -> {
            insideWindow.set(true);
            // The directory has already committed the new generation...
            assertEquals(h.bId, h.directory.resolve(h.aKey).orElseThrow().region());
            RegionRoute only = h.directory.snapshot().routes().get(h.aKey);
            assertEquals(h.bId, only.region(), "exactly one directory owner for the key: no dual authority");
            // ...while the derived ledger still names the old owner, so no object has any owner.
            assertTrue(h.objects.current("pub-entity").isEmpty());
            // Stale old-owner mutation is impossible inside the window.
            assertThrows(IllegalStateException.class,
                () -> h.objects.requireCurrentOwner("pub-entity", new OwnerEpoch(0), h.aId));
            // Premature new-owner mutation is impossible inside the window.
            assertThrows(IllegalStateException.class,
                () -> h.objects.requireCurrentOwner("pub-entity", new OwnerEpoch(1), h.bId));
        });
        coordinator.request(new TopologyCoordinator.Transition(Set.of(h.aId, h.bId), Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        assertTrue(coordinator.advance());
        assertTrue(insideWindow.get(), "the probe ran inside the committed publication window");
        ObjectOwnership after = h.objects.current("pub-entity").orElseThrow();
        assertEquals(h.bId, after.owner());
        assertEquals(1, after.objectEpoch().value(), "reconciled exactly once after the window");
        // The publication path is synchronous: no sleep, wait, or join in the coordinator.
        String coordinatorSource = Files.readString(
            Path.of("src", "main", "java", "dev", "phoenium", "topology", "TopologyCoordinator.java"));
        assertFalse(coordinatorSource.contains("Thread.sleep"));
        assertFalse(coordinatorSource.contains(".wait("));
        assertFalse(coordinatorSource.contains(".join("));
    }

    private static void publishSquare(MovementHarness harness, int radius) {
        Map<RoutingKey, RegionId> owners = new LinkedHashMap<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                owners.put(new RoutingKey(harness.world, x, z), harness.aId);
            }
        }
        owners.put(harness.bKey, harness.bId);
        assertTrue(harness.topology(owners));
    }

    /**
     * Deterministic synthetic execution seam used only by the movement acceptance cases. It models
     * a classified continuous operation as its complete routing-key footprint: source, every swept
     * waypoint, conservative query padding, and explicitly declared callback/query cells. The
     * complete footprint is checked against the executor's frozen domain before the first simulated
     * lookup or position store. It deliberately has no relocation branch.
     */
    private static final class MovementHarness implements ObjectOperationExecutor {
        final CoreFixture fixture = new CoreFixture();
        final WorldId world = fixture.world;
        final RegionId aId = fixture.aId, bId = fixture.bId;
        final RoutingKey aKey = fixture.aKey, bKey = fixture.bKey;
        final RegionRuntime a = fixture.a, b = fixture.b;
        final Map<RegionId, RegionRuntime> runtimes = fixture.runtimes;
        final RegionDirectory directory = fixture.directory;
        final FailureBarrier barrier = fixture.barrier;
        final RegionScheduler scheduler = new RegionScheduler(runtimes, directory, barrier, null, this);
        final OwnerGateway gateway = new OwnerGateway(directory, runtimes, scheduler);
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final ObjectOwnerGateway objectsGateway = new ObjectOwnerGateway(objects, directory, gateway);
        final Map<String, SweepRequest> sweeps = new ConcurrentHashMap<>();
        final Map<String, RoutingKey> positionState = new ConcurrentHashMap<>();
        int aReads;
        int bReads;
        int aWrites;
        int bWrites;
        int entityMutations;
        RegionId lastMutationRegion;

        OwnedObjectEnvelope envelope(String objectId, OwnerEpoch epoch, String operationKind,
                                     AdmissionPolicy policy, long sequence) {
            return new OwnedObjectEnvelope(objectId, epoch, operationKind, "movement-test",
                new TaskCorrelationId(sequence), new TaskSequence(sequence), policy);
        }

        TopologyCoordinator coordinator() {
            return new TopologyCoordinator(directory, runtimes, gateway, objects);
        }

        boolean topology(Map<RoutingKey, RegionId> owners) {
            TopologyCoordinator coordinator = coordinator();
            coordinator.request(new TopologyCoordinator.Transition(Set.of(aId, bId), owners));
            return coordinator.advance();
        }

        void requestSweep(String objectId, TaskCorrelationId correlationId, List<RoutingKey> waypoints,
                          int padding, Set<RoutingKey> extraFootprint) {
            if (padding < 0) throw new IllegalArgumentException("negative padding");
            sweeps.put(objectId, new SweepRequest(correlationId, List.copyOf(waypoints), padding,
                Set.copyOf(extraFootprint)));
        }

        @Override
        public void execute(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
            ObjectOwnership binding = objects.requireCurrentOwner(operation.objectId(),
                operation.expectedObjectEpoch(), runtime.id());
            if ("movement".equals(operation.operationKind())) {
                SweepRequest request = sweeps.remove(operation.objectId());
                if (request == null) throw new IllegalStateException("missing classified movement footprint");
                applySweep(runtime, binding, request);
                return;
            }
            countWrite(runtime);
            entityMutations++;
            lastMutationRegion = runtime.id();
        }

        @Override
        public void execute(RegionRuntime runtime, TaskPayload.ChunkCompletion completion) {
            throw new IllegalStateException("movement harness has no chunk-completion operation");
        }

        private void applySweep(RegionRuntime runtime, ObjectOwnership binding, SweepRequest request) {
            FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, runtime.id());
            long minX = binding.routingKey().x();
            long maxX = minX;
            long minZ = binding.routingKey().z();
            long maxZ = minZ;
            RoutingKey finalKey = binding.routingKey();
            for (RoutingKey waypoint : request.waypoints()) {
                if (!waypoint.world().equals(binding.routingKey().world())) {
                    throw new ClosureEscapeException("movement footprint crosses world identity");
                }
                minX = Math.min(minX, waypoint.x());
                maxX = Math.max(maxX, waypoint.x());
                minZ = Math.min(minZ, waypoint.z());
                maxZ = Math.max(maxZ, waypoint.z());
                finalKey = waypoint;
            }
            // This is the preflight: no routing lookup of a footprint key or simulated store occurs
            // until both the full swept/query box and callback/query set are inside the frozen D(R,L).
            authority.requireWithinBox(binding.routingKey().world(), minX - request.padding(),
                maxX + request.padding(), minZ - request.padding(), maxZ + request.padding());
            authority.requireWithin(request.extraFootprint());
            countRead(runtime);
            if (!finalKey.equals(binding.routingKey())) {
                objects.updateRoutingKey(binding.objectId(), finalKey, binding.objectEpoch());
            }
            countWrite(runtime);
            positionState.put(binding.objectId(), finalKey);
            lastMutationRegion = runtime.id();
        }

        private void countRead(RegionRuntime runtime) {
            if (runtime.id().equals(aId)) aReads++;
            else if (runtime.id().equals(bId)) bReads++;
            else throw new IllegalStateException("unknown mutation region " + runtime.id());
        }

        private void countWrite(RegionRuntime runtime) {
            if (runtime.id().equals(aId)) aWrites++;
            else if (runtime.id().equals(bId)) bWrites++;
            else throw new IllegalStateException("unknown mutation region " + runtime.id());
        }

        private record SweepRequest(TaskCorrelationId correlationId, List<RoutingKey> waypoints,
                                    int padding, Set<RoutingKey> extraFootprint) { }
    }
}
