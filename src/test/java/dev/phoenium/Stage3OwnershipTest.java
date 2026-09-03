package dev.phoenium;

import dev.phoenium.chunk.ChunkCompletionGateway;
import dev.phoenium.chunk.ChunkWorkRequest;
import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.ownership.ObjectOperationExecutor;
import dev.phoenium.ownership.ObjectOwnerGateway;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectEnvelope;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.TopologyCoordinator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic Stage 3 ownership regressions; all use serial scheduler execution and no Minecraft thread shortcut. */
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
        ObjectOwnership old = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        ObjectOwnership moved = h.objects.move("entity-1", h.bKey);
        assertEquals(h.bKey, moved.routingKey());
        assertEquals(old.objectEpoch().next(), moved.objectEpoch());
        assertEquals(ObjectOwnerGateway.Delivery.REROUTED, h.objectsGateway.submit(h.envelope("entity-1", old.objectEpoch(), "entity-mutation", AdmissionPolicy.REROUTE, 1)));
        h.scheduler.advanceOne();
        assertEquals(1, h.entityMutations);
        assertEquals(h.bId, h.lastMutationRegion);
        assertEquals(1, h.objects.liveCount());
    }

    @Test void p303StaleEntityEnvelopeCannotMutateOldOwner() {
        Harness h = new Harness();
        ObjectOwnership old = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.aKey);
        h.objects.move("entity-1", h.bKey);
        assertEquals(ObjectOwnerGateway.Delivery.REJECTED_STALE, h.objectsGateway.submit(h.envelope("entity-1", old.objectEpoch(), "entity-mutation", AdmissionPolicy.REJECT, 1)));
        assertEquals(0, h.a.inboxSize()); assertEquals(0, h.b.inboxSize());
    }

    @Test void p304PlayerOwnershipAndNetworkIngressUseCurrentOwner() {
        Harness h = new Harness();
        ObjectOwnership player = h.objects.admit("player-1", OwnedObjectKind.PLAYER, h.aKey);
        h.objects.move("player-1", h.bKey);
        assertEquals(ObjectOwnerGateway.Delivery.REROUTED, h.objectsGateway.submit(h.envelope("player-1", player.objectEpoch(), "player-movement", AdmissionPolicy.REROUTE, 1)));
        h.scheduler.advanceOne();
        assertEquals(1, h.playerMutations); assertEquals(h.bId, h.lastMutationRegion);
    }

    @Test void p305AsyncChunkCompletionReroutesAfterTopologyChangeAndNeverUsesProducerMutation() {
        Harness h = new Harness();
        ChunkCompletionGateway chunks = new ChunkCompletionGateway(h.directory, h.gateway);
        ChunkWorkRequest request = new ChunkWorkRequest(h.world, h.aKey, 7, h.directory.snapshot().generation(), "full", new TaskCorrelationId(7));
        TopologyCoordinator topology = new TopologyCoordinator(h.directory, h.runtimes, h.gateway);
        topology.request(new TopologyCoordinator.Transition(Set.of(h.aId, h.bId), Map.of(h.aKey, h.bId, h.bKey, h.bId)));
        assertTrue(topology.advance());
        assertEquals(ChunkCompletionGateway.Delivery.REROUTED_CURRENT_OWNER, chunks.complete(request));
        h.scheduler.advanceOne();
        assertEquals(1, h.chunkApplications); assertEquals(h.bId, h.lastMutationRegion);
    }

    @Test void rOuterLease01ForeignObjectCannotMutateInsideAnotherLease() {
        Harness h = new Harness();
        ObjectOwnership entity = h.objects.admit("entity-1", OwnedObjectKind.ENTITY, h.bKey);
        try (var lease = h.a.leases().acquire(); var ignored = dev.phoenium.ownership.OwnershipContext.enter(h.aId, lease.epoch(), dev.phoenium.ownership.OperationCategory.OWNER_TASK)) {
            assertThrows(IllegalStateException.class, () -> h.objects.requireCurrentOwner("entity-1", entity.objectEpoch(), h.aId));
        }
    }

    private static final class Harness implements ObjectOperationExecutor {
        final CoreFixture fixture = new CoreFixture();
        final dev.phoenium.core.WorldId world = fixture.world;
        final dev.phoenium.region.RegionId aId = fixture.aId, bId = fixture.bId;
        final dev.phoenium.topology.RoutingKey aKey = fixture.aKey, bKey = fixture.bKey;
        final RegionRuntime a = fixture.a, b = fixture.b;
        final Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes = fixture.runtimes;
        final dev.phoenium.topology.RegionDirectory directory = fixture.directory;
        final RegionScheduler scheduler = new RegionScheduler(runtimes, directory, fixture.barrier, null, this);
        final OwnerGateway gateway = new OwnerGateway(directory, runtimes, scheduler);
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final ObjectOwnerGateway objectsGateway = new ObjectOwnerGateway(objects, directory, gateway);
        int entityMutations, playerMutations, chunkApplications;
        dev.phoenium.region.RegionId lastMutationRegion;
        OwnedObjectEnvelope envelope(String id, OwnerEpoch epoch, String kind, AdmissionPolicy policy, long sequence) {
            return new OwnedObjectEnvelope(id, epoch, kind, "network-ingress", new TaskCorrelationId(sequence), new TaskSequence(sequence), policy);
        }
        @Override public void execute(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
            ObjectOwnership binding = objects.requireCurrentOwner(operation.objectId(), operation.expectedObjectEpoch(), runtime.id());
            if (binding.kind() == OwnedObjectKind.PLAYER) playerMutations++; else entityMutations++;
            lastMutationRegion = runtime.id();
        }
        @Override public void execute(RegionRuntime runtime, TaskPayload.ChunkCompletion completion) { chunkApplications++; lastMutationRegion = runtime.id(); }
    }
}
