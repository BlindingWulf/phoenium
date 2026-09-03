package dev.phoenium.ownership;

import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.topology.RegionRoute;
import java.util.Objects;

/** Resolves current object ownership before queueing, then requires the executor to validate it again. */
public final class ObjectOwnerGateway {
    private final ObjectOwnershipRegistry objects;
    private final dev.phoenium.topology.RegionDirectory directory;
    private final OwnerGateway gateway;
    private long staleReroutes;
    private long staleRejections;

    public ObjectOwnerGateway(ObjectOwnershipRegistry objects, dev.phoenium.topology.RegionDirectory directory, OwnerGateway gateway) {
        this.objects = Objects.requireNonNull(objects, "objects"); this.directory = Objects.requireNonNull(directory, "directory"); this.gateway = Objects.requireNonNull(gateway, "gateway");
        gateway.scheduler().installObjectDeliveryValidator(this);
    }
    public Delivery submit(OwnedObjectEnvelope requested) {
        ObjectOwnership current = objects.current(requested.objectId()).orElse(null);
        if (current == null) { staleRejections++; return Delivery.REJECTED_ABSENT; }
        boolean stale = !current.objectEpoch().equals(requested.expectedObjectEpoch());
        if (stale && requested.admissionPolicy() == dev.phoenium.task.AdmissionPolicy.REJECT) { staleRejections++; return Delivery.REJECTED_STALE; }
        OwnedObjectEnvelope delivered = stale ? requested.rerouted(current.objectEpoch()) : requested;
        RegionRoute route = directory.resolve(current.routingKey()).orElseThrow();
        RegionTaskEnvelope envelope = new RegionTaskEnvelope("owned-" + delivered.operationKind(),
            new TaskPayload.OwnedObjectOperation(delivered.objectId(), delivered.expectedObjectEpoch(), delivered.operationKind(), delivered.correlationId()),
            current.routingKey(), route.generation(), route.ownerEpoch(), delivered.origin(), delivered.correlationId(), delivered.sequence(), delivered.admissionPolicy());
        OwnerGateway.Delivery gatewayDelivery = gateway.submit(envelope);
        if (gatewayDelivery == OwnerGateway.Delivery.QUEUED || gatewayDelivery == OwnerGateway.Delivery.REROUTED || gatewayDelivery == OwnerGateway.Delivery.DEFERRED) {
            if (stale) staleReroutes++;
            return stale ? Delivery.REROUTED : Delivery.QUEUED;
        }
        staleRejections++;
        return Delivery.REJECTED_GATEWAY;
    }
    public long staleReroutes() { return staleReroutes; }
    public long staleRejections() { return staleRejections; }
    /**
     * Runs immediately before mutation, after the owner inbox has been polled. An envelope earns
     * no permanent authority by being queued in a former owner's inbox.
     */
    public boolean revalidateForExecution(RegionRuntime runtime, RegionTaskEnvelope queued) {
        if (!(queued.payload() instanceof TaskPayload.OwnedObjectOperation operation)) return true;
        ObjectOwnership current = objects.current(operation.objectId()).orElse(null);
        boolean valid = current != null
            && current.objectEpoch().equals(operation.expectedObjectEpoch())
            && current.owner().equals(runtime.id())
            && directory.resolve(current.routingKey()).map(route -> route.region().equals(runtime.id())).orElse(false);
        if (valid) return true;
        OwnedObjectEnvelope retry = new OwnedObjectEnvelope(operation.objectId(), operation.expectedObjectEpoch(), operation.operationKind(),
            queued.origin(), operation.correlationId(), queued.sequence(), queued.admissionPolicy());
        submit(retry);
        return false;
    }
    public enum Delivery { QUEUED, REROUTED, REJECTED_STALE, REJECTED_ABSENT, REJECTED_GATEWAY }
}
