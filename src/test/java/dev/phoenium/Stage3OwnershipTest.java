package dev.phoenium;

import dev.phoenium.chunk.ChunkCompletionGateway;
import dev.phoenium.chunk.ChunkCustody;
import dev.phoenium.chunk.ChunkWorkRequest;
import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
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
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyCoordinator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic Stage 3 ownership regressions; all use serial scheduler execution and no Minecraft
 * thread shortcut. Topology enclosure: a key change re-resolves the owner; a same-owner re-publish
 * never bumps the object epoch; only a real owner change bumps it exactly once at the quiescent
 * publication boundary. The object ledger is derived; the RegionDirectory is authoritative.
 */
class Stage3OwnershipTest {
    @Test void p301RealEntityMutationRequiresCurrentOwnerEpoch() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED, h.objectsGateway.submit(h.envelope("entity-1", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        h.scheduler.advanceOne();
        assertEquals(1, h.entityMutations);
        assertThrows(IllegalStateException.class, () -> h.objects.requireCurrentOwner("entity-1", entity.objectEpoch(), h.aId));
    }

    @Test void p302EntityBoundaryTransferInvalidatesOldOwnerAndDoesNotDuplicate() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        assertTrue(h.topology(Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        ObjectOwnership moved = h.objects.current("entity-1").orElseThrow();
        assertEquals(h.aKey, moved.routingKey());
        assertEquals(h.bId, moved.owner());
        assertEquals(1, moved.objectEpoch().value());
        assertEquals(ObjectOwnerGateway.Delivery.REROUTED, h.objectsGateway.submit(h.envelope("entity-1", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REROUTE, 1)));
        assertEquals(0, h.a.inboxSize());
        assertEquals(1, h.b.inboxSize());
        h.scheduler.advanceOne();
        assertEquals(1, h.entityMutations);
        assertEquals(h.bId, h.lastMutationRegion);
        assertEquals(1, h.objects.liveCount());
    }

    @Test void p303StaleEntityEnvelopeCannotMutateOldOwner() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        assertTrue(h.topology(Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        assertEquals(ObjectOwnerGateway.Delivery.REJECTED_STALE, h.objectsGateway.submit(h.envelope("entity-1", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        assertEquals(0, h.a.inboxSize());
        assertEquals(0, h.b.inboxSize());
        h.scheduler.advanceOne();
        assertEquals(0, h.entityMutations);
    }

    @Test void p304PlayerOwnershipAndNetworkIngressUseCurrentOwner() {
        Harness h = new Harness();
        ObjectOwnership player = h.objects.admit("player-1", OwnedObjectKind.PLAYER, h.aKey);
        assertTrue(h.topology(Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        assertEquals(ObjectOwnerGateway.Delivery.REROUTED, h.objectsGateway.submit(h.envelope("player-1", player.objectEpoch(), "player-movement", AdmissionPolicy.REROUTE, 1)));
        h.scheduler.advanceOne();
        assertEquals(1, h.playerMutations);
        assertEquals(h.bId, h.lastMutationRegion);
    }

    @Test void p305AsyncChunkCompletionReroutesAfterTopologyChangeAndNeverUsesProducerMutation() {
        Harness h = new Harness();
        ChunkWorkRequest request = new ChunkWorkRequest(h.world, h.aKey, 7, h.directory.snapshot().generation(), "full", new TaskCorrelationId(7));
        h.custody.request(ChunkCustody.id(request));
        h.chunkRequests.put(request.correlationId(), request);
        assertTrue(h.topology(Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        assertEquals(ChunkCompletionGateway.Delivery.REROUTED_CURRENT_OWNER, h.chunkGateway.complete(request));
        assertEquals(0, h.a.inboxSize());
        assertEquals(1, h.b.inboxSize());
        h.scheduler.advanceOne();
        assertEquals(1, h.chunkApplications);
        assertEquals(h.bId, h.lastMutationRegion);
        assertEquals(ChunkCustody.Stage.APPLIED, h.custody.stage(ChunkCustody.id(request)).orElseThrow());
        assertEquals(h.bId, h.custody.claimedOwner(ChunkCustody.id(request)).orElseThrow());
        assertEquals(0, h.custody.duplicateClaims());
    }

    @Test void rOuterLease01ForeignObjectCannotMutateInsideAnotherLease() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.bKey);
        try (var lease = h.a.leases().acquire(); var ignored = OwnershipContext.enter(h.aId, lease.epoch(), OperationCategory.OWNER_TASK)) {
            assertThrows(IllegalStateException.class, () -> h.objects.requireCurrentOwner("entity-1", entity.objectEpoch(), h.aId));
        }
    }

    @Test void d01SameOwnerTopologyRepublishPreservesObjectEpoch() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED, h.objectsGateway.submit(h.envelope("entity-1", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        assertTrue(h.topology(Map.of(h.aKey, h.aId, h.bKey, h.bId)));
        ObjectOwnership current = h.objects.current("entity-1").orElseThrow();
        assertEquals(h.aId, current.owner());
        assertEquals(h.aKey, current.routingKey());
        assertEquals(0, current.objectEpoch().value());
        assertEquals(2, current.topologyGeneration().value());
        h.scheduler.advanceOne();
        assertEquals(1, h.entityMutations);
        assertEquals(h.aId, h.lastMutationRegion);
        assertEquals(0, h.a.inboxSize());
    }

    @Test void d02SameOwnerRepublishRefreshesEveryBindingWithoutEpochBump() {
        Harness h = new Harness();
        h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        h.objects.admit("entity-2", OwnedObjectKind.ENTITY, h.bKey);
        assertTrue(h.topology(Map.of(h.aKey, h.aId, h.bKey, h.bId)));
        ObjectOwnership e1 = h.objects.current("entity-1").orElseThrow();
        ObjectOwnership e2 = h.objects.current("entity-2").orElseThrow();
        assertEquals(h.aId, e1.owner());
        assertEquals(0, e1.objectEpoch().value());
        assertEquals(2, e1.topologyGeneration().value());
        assertEquals(h.bId, e2.owner());
        assertEquals(0, e2.objectEpoch().value());
        assertEquals(2, e2.topologyGeneration().value());
        assertEquals(2, h.directory.snapshot().generation().value());
    }

    @Test void d04aCrossOwnerTransitionDropsQueuedRejectEnvelope() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED, h.objectsGateway.submit(h.envelope("entity-1", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        assertTrue(h.topology(Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        h.scheduler.advanceOne();
        assertEquals(0, h.entityMutations);
        assertEquals(0, h.a.inboxSize());
        assertEquals(0, h.b.inboxSize());
    }

    @Test void d04bCrossOwnerTransitionReroutesQueuedRerouteEnvelopeExactlyOnce() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED, h.objectsGateway.submit(h.envelope("entity-1", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REROUTE, 1)));
        assertTrue(h.topology(Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        h.scheduler.advanceOne();
        assertEquals(0, h.entityMutations);
        assertEquals(0, h.a.inboxSize());
        assertEquals(1, h.b.inboxSize());
        h.scheduler.advanceOne();
        assertEquals(1, h.entityMutations);
        assertEquals(h.bId, h.lastMutationRegion);
        h.scheduler.advanceOne();
        assertEquals(1, h.entityMutations);
    }

    @Test void m01SameOwnerRoutingKeyUpdateKeepsEpochAndExecution() {
        Harness h = new Harness();
        RoutingKey movedKey = new RoutingKey(h.world, 1, 0);
        h.directory.publish(new TopologyGeneration(2), Map.of(
            h.aKey, new RegionRoute(h.aId, new TopologyGeneration(2), h.a.ownerEpoch()),
            h.bKey, new RegionRoute(h.bId, new TopologyGeneration(2), h.b.ownerEpoch()),
            movedKey, new RegionRoute(h.aId, new TopologyGeneration(2), h.a.ownerEpoch())));
        ObjectOwnership entity = h.objects.admit("m1-entity", OwnedObjectKind.ENTITY, h.aKey);
        ObjectOwnership updated = h.objects.updateRoutingKey("m1-entity", movedKey, new OwnerEpoch(0));
        assertEquals(movedKey, updated.routingKey());
        assertEquals(h.aId, updated.owner());
        assertEquals(0, updated.objectEpoch().value());
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED, h.objectsGateway.submit(h.envelope("m1-entity", new OwnerEpoch(0), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        h.scheduler.advanceOne();
        assertEquals(1, h.entityMutations);
        assertEquals(h.aId, h.lastMutationRegion);
    }

    @Test void m02DeferredTransitionMailboxReentersCurrentDirectory() {
        Harness h = new Harness();
        TopologyCoordinator coordinator = h.coordinator();
        coordinator.request(new TopologyCoordinator.Transition(Set.of(h.aId, h.bId), Map.of(h.aKey, h.aId, h.bKey, h.bId)));
        RegionTaskEnvelope deferred = h.fixture.task(h.bKey, new TaskPayload.Append("deferred"), AdmissionPolicy.REROUTE, 1);
        assertEquals(OwnerGateway.Delivery.DEFERRED, h.gateway.submit(deferred));
        assertEquals(1, h.b.transitionMailboxSize());
        assertEquals(0, h.a.transitionMailboxSize());
        assertTrue(coordinator.advance());
        assertEquals(0, h.b.transitionMailboxSize());
        assertEquals(1, h.b.inboxSize());
        h.scheduler.advanceOne();
        assertEquals(List.of("deferred"), h.b.immutableLog());
        assertTrue(h.a.immutableLog().isEmpty());
    }

    @Test void m03ActiveLeaseBlocksQuiescentTopologyPublication() {
        Harness h = new Harness();
        h.objects.admit("m3-entity", OwnedObjectKind.ENTITY, h.aKey);
        TopologyCoordinator coordinator = h.coordinator();
        RegionLease lease = h.a.leases().acquire();
        try (var ignored = OwnershipContext.enter(h.aId, lease.epoch(), OperationCategory.OWNER_TASK)) {
            coordinator.request(new TopologyCoordinator.Transition(Set.of(h.aId, h.bId), Map.of(h.aKey, h.bId, h.bKey, h.bId)));
            assertFalse(coordinator.advance());
            assertTrue(coordinator.hasPendingTransition());
            ObjectOwnership held = h.objects.current("m3-entity").orElseThrow();
            assertEquals(h.aId, held.owner());
            assertEquals(0, held.objectEpoch().value());
        }
        lease.close();
        assertTrue(coordinator.advance());
        ObjectOwnership moved = h.objects.current("m3-entity").orElseThrow();
        assertEquals(h.bId, moved.owner());
        assertEquals(1, moved.objectEpoch().value());
        assertThrows(IllegalStateException.class, () -> h.a.leases().requireCurrent(lease.epoch()));
    }

    @Test void m04ResidentObjectKeepsEpochAcrossCrossOwnerTransitions() {
        Harness h = new Harness();
        h.objects.admit("m4-entity", OwnedObjectKind.ENTITY, h.aKey);
        h.objects.admit("m4-resident", OwnedObjectKind.ENTITY, h.bKey);
        assertTrue(h.topology(Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        ObjectOwnership e1 = h.objects.current("m4-entity").orElseThrow();
        ObjectOwnership r1 = h.objects.current("m4-resident").orElseThrow();
        assertEquals(h.bId, e1.owner());
        assertEquals(1, e1.objectEpoch().value());
        assertEquals(h.aKey, e1.routingKey());
        assertEquals(h.bId, r1.owner());
        assertEquals(0, r1.objectEpoch().value());
        assertTrue(h.topology(Map.of(h.aKey, h.aId, h.bKey, h.bId)));
        ObjectOwnership e2 = h.objects.current("m4-entity").orElseThrow();
        ObjectOwnership r2 = h.objects.current("m4-resident").orElseThrow();
        assertEquals(h.aId, e2.owner());
        assertEquals(2, e2.objectEpoch().value());
        assertEquals(h.aKey, e2.routingKey());
        assertEquals(h.bId, r2.owner());
        assertEquals(0, r2.objectEpoch().value());
    }

    @Test void m05TopologyPublishWithoutRegistryNeverGrantsOwnership() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("m5-entity", OwnedObjectKind.ENTITY, h.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED, h.objectsGateway.submit(h.envelope("m5-entity", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REROUTE, 1)));
        TopologyCoordinator bare = new TopologyCoordinator(h.directory, h.runtimes, h.gateway);
        bare.request(new TopologyCoordinator.Transition(Set.of(h.aId, h.bId), Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        assertTrue(bare.advance());
        assertTrue(h.objects.current("m5-entity").isEmpty());
        h.scheduler.advanceOne();
        assertEquals(0, h.entityMutations);
        assertEquals(0, h.a.inboxSize());
        assertEquals(0, h.b.inboxSize());
    }

    @Test void m06UnknownChunkAndRemovedObjectFailClosed() {
        Harness h = new Harness();
        RoutingKey unknownKey = new RoutingKey(h.world, 42, 42);
        ChunkWorkRequest unknown = new ChunkWorkRequest(h.world, unknownKey, 7, h.directory.snapshot().generation(), "full", new TaskCorrelationId(77));
        h.custody.request(ChunkCustody.id(unknown));
        assertEquals(ChunkCompletionGateway.Delivery.REJECTED_UNKNOWN_CHUNK, h.chunkGateway.complete(unknown));
        assertEquals(ChunkCustody.Stage.CANCELLED, h.custody.stage(ChunkCustody.id(unknown)).orElseThrow());
        assertEquals(0, h.chunkApplications);
        ObjectOwnership entity = h.objects.admit("m6-entity", OwnedObjectKind.ENTITY, h.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED, h.objectsGateway.submit(h.envelope("m6-entity", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        h.objects.remove("m6-entity");
        assertEquals(0, h.objects.liveCount());
        h.scheduler.advanceOne();
        assertEquals(0, h.entityMutations);
        assertEquals(0, h.a.inboxSize());
        assertEquals(ObjectOwnerGateway.Delivery.REJECTED_ABSENT, h.objectsGateway.submit(h.envelope("m6-entity", entity.objectEpoch(), "entity-mutation", AdmissionPolicy.REROUTE, 1)));
    }

    @Test void chunkCustodyStagesAreExactlyOnceAndTerminalSafe() {
        ChunkCustody custody = new ChunkCustody();
        WorldId world = new WorldId("test");
        RoutingKey key = new RoutingKey(world, 0, 0);
        RegionId owner = new RegionId(world, "b");
        ChunkWorkRequest request = new ChunkWorkRequest(world, key, 3, new TopologyGeneration(1), "full", new TaskCorrelationId(3));
        ChunkCustody.CustodyId id = ChunkCustody.id(request);
        custody.request(id);
        custody.request(id);
        assertEquals(ChunkCustody.Stage.REQUESTED, custody.stage(id).orElseThrow());
        custody.ready(id);
        custody.ready(id);
        assertEquals(ChunkCustody.Stage.READY, custody.stage(id).orElseThrow());
        assertThrows(IllegalStateException.class, () -> custody.request(id));
        custody.queued(id);
        assertEquals(ChunkCustody.Stage.QUEUED, custody.stage(id).orElseThrow());
        assertTrue(custody.claim(id, owner));
        assertFalse(custody.claim(id, owner));
        assertEquals(1, custody.duplicateClaims());
        custody.apply(id);
        assertEquals(ChunkCustody.Stage.APPLIED, custody.stage(id).orElseThrow());
        assertEquals(owner, custody.claimedOwner(id).orElseThrow());
        custody.cancel(id);
        assertEquals(ChunkCustody.Stage.APPLIED, custody.stage(id).orElseThrow());
        assertThrows(IllegalStateException.class, () -> custody.ready(id));
        assertThrows(IllegalStateException.class, () -> custody.queued(id));
        ChunkCustody.CustodyId failedId = new ChunkCustody.CustodyId(world, key, 4, new TopologyGeneration(1));
        assertThrows(IllegalStateException.class, () -> custody.fail(failedId));
        custody.request(failedId);
        custody.fail(failedId);
        assertEquals(ChunkCustody.Stage.FAILED, custody.stage(failedId).orElseThrow());
        assertFalse(custody.claim(failedId, owner));
        assertEquals(1, custody.duplicateClaims());
        assertThrows(IllegalStateException.class, () -> custody.queued(failedId));
        custody.cancel(new ChunkCustody.CustodyId(world, key, 5, new TopologyGeneration(1)));
        ChunkCustody.CustodyId cancelledId = new ChunkCustody.CustodyId(world, key, 6, new TopologyGeneration(1));
        custody.request(cancelledId);
        custody.cancel(cancelledId);
        assertEquals(ChunkCustody.Stage.CANCELLED, custody.stage(cancelledId).orElseThrow());
        assertFalse(custody.claim(cancelledId, owner));
        assertEquals(1, custody.duplicateClaims());
    }

    private static final class Harness implements ObjectOperationExecutor {
        final CoreFixture fixture = new CoreFixture();
        final WorldId world = fixture.world;
        final RegionId aId = fixture.aId, bId = fixture.bId;
        final RoutingKey aKey = fixture.aKey, bKey = fixture.bKey;
        final RegionRuntime a = fixture.a, b = fixture.b;
        final Map<RegionId, RegionRuntime> runtimes = fixture.runtimes;
        final RegionDirectory directory = fixture.directory;
        final RegionScheduler scheduler = new RegionScheduler(runtimes, directory, fixture.barrier, null, this);
        final OwnerGateway gateway = new OwnerGateway(directory, runtimes, scheduler);
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final ObjectOwnerGateway objectsGateway = new ObjectOwnerGateway(objects, directory, gateway);
        final ChunkCustody custody = new ChunkCustody();
        final ChunkCompletionGateway chunkGateway = new ChunkCompletionGateway(directory, gateway, custody);
        final Map<TaskCorrelationId, ChunkWorkRequest> chunkRequests = new ConcurrentHashMap<>();
        int entityMutations, playerMutations, chunkApplications, chunkDuplicates;
        RegionId lastMutationRegion;
        OwnedObjectEnvelope envelope(String id, OwnerEpoch epoch, String kind, AdmissionPolicy policy, long sequence) {
            return new OwnedObjectEnvelope(id, epoch, kind, "network-ingress", new TaskCorrelationId(sequence), new TaskSequence(sequence), policy);
        }
        TopologyCoordinator coordinator() { return new TopologyCoordinator(directory, runtimes, gateway, objects); }
        boolean topology(Map<RoutingKey, RegionId> owners) {
            TopologyCoordinator coordinator = coordinator();
            coordinator.request(new TopologyCoordinator.Transition(Set.of(aId, bId), owners));
            return coordinator.advance();
        }
        @Override public void execute(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
            ObjectOwnership binding = objects.requireCurrentOwner(operation.objectId(), operation.expectedObjectEpoch(), runtime.id());
            if (binding.kind() == OwnedObjectKind.PLAYER) playerMutations++; else entityMutations++;
            lastMutationRegion = runtime.id();
        }
        @Override public void execute(RegionRuntime runtime, TaskPayload.ChunkCompletion completion) {
            ChunkWorkRequest request = chunkRequests.get(completion.correlationId());
            if (request == null || request.requestGeneration() != completion.requestGeneration()) {
                chunkRequests.remove(completion.correlationId());
                throw new IllegalStateException("unknown or stale chunk completion");
            }
            var route = directory.resolve(request.chunkKey()).orElse(null);
            if (route == null) {
                chunkRequests.remove(completion.correlationId());
                custody.cancel(ChunkCustody.id(request));
                return;
            }
            if (!route.region().equals(runtime.id())) {
                throw new IllegalStateException("chunk completion arrived at a foreign region");
            }
            ChunkCustody.CustodyId custodyId = ChunkCustody.id(request);
            if (!custody.claim(custodyId, runtime.id())) {
                chunkDuplicates++;
                return;
            }
            chunkRequests.remove(completion.correlationId());
            custody.apply(custodyId);
            chunkApplications++;
            lastMutationRegion = runtime.id();
        }
    }
}
