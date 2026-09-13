package dev.phoenium;

import dev.phoenium.ownership.ObjectOperationExecutor;
import dev.phoenium.ownership.ObjectOwnerGateway;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.ownership.OwnedObjectEnvelope;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.ownership.OwnershipContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pearl operation uses the existing identity-only gateway and follows a committed owner move. */
class PearlCleanupRoutingTest implements ObjectOperationExecutor {
    private final CoreFixture fixture = new CoreFixture();
    private final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(fixture.directory);
    private final RegionScheduler scheduler = new RegionScheduler(fixture.runtimes, fixture.directory,
        fixture.barrier, null, this);
    private final OwnerGateway gateway = new OwnerGateway(fixture.directory, fixture.runtimes, scheduler);
    private final ObjectOwnerGateway objectGateway = new ObjectOwnerGateway(objects, fixture.directory, gateway);
    private final AtomicInteger executions = new AtomicInteger();
    private RegionId executionRegion;

    @Test void queuedPearlCleanupReroutesCurrentOwnerAndExecutesOnce() {
        ObjectOwnership pearl = objects.admit("pearl-route", OwnedObjectKind.ENTITY, fixture.aKey);
        assertEquals(ObjectOwnerGateway.Delivery.QUEUED,
            objectGateway.submit(new OwnedObjectEnvelope("pearl-route", pearl.objectEpoch(),
                dev.phoenium.live.PearlMembershipLedger.DISCARD_OPERATION, "test-pearl",
                new TaskCorrelationId(7001), new TaskSequence(7001), AdmissionPolicy.REROUTE)));

        // This is the already-committed A1 state: the identity-only envelope remains in A while
        // current object resolution points at B.  No owner callback is used to perform the move.
        objects.transitionOwner("pearl-route", fixture.bKey, pearl.objectEpoch());
        assertEquals(0, executions.get());
        scheduler.advanceOne();
        scheduler.advanceOne();

        assertEquals(1, executions.get());
        assertEquals(fixture.bId, executionRegion);
        assertFalse(fixture.a.leases().hasActiveLease());
        assertFalse(fixture.b.leases().hasActiveLease());
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
        assertEquals(dev.phoenium.live.PearlMembershipLedger.DISCARD_OPERATION, operation.operationKind());
        objects.requireCurrentOwner(operation.objectId(), operation.expectedObjectEpoch(), runtime.id());
        assertTrue(OwnershipContext.current().isPresent());
        executions.incrementAndGet();
        executionRegion = runtime.id();
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.ChunkCompletion completion) {
        fail("unexpected chunk completion");
    }
}
