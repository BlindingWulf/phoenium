package dev.phoenium.chunk;

import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import java.util.Objects;

/** Boundary for asynchronous producers: completion is metadata routed to a current owner, never a live mutation. */
public final class ChunkCompletionGateway {
    private final RegionDirectory directory;
    private final OwnerGateway gateway;
    private final ChunkCustody custody;
    private long reroutes;
    private long rejections;

    public ChunkCompletionGateway(RegionDirectory directory, OwnerGateway gateway) { this(directory, gateway, null); }

    /** A non-null custody ledger records the staged completion lifecycle so applications are exactly-once. */
    public ChunkCompletionGateway(RegionDirectory directory, OwnerGateway gateway, ChunkCustody custody) {
        this.directory = Objects.requireNonNull(directory); this.gateway = Objects.requireNonNull(gateway); this.custody = custody;
    }
    public Delivery complete(ChunkWorkRequest request) {
        ChunkCustody.CustodyId custodyId = custody == null ? null : ChunkCustody.id(request);
        if (custodyId != null && !custody.isCurrent(custodyId)) {
            // A newer request incarnation owns this coordinate now. Do not advance the old stage,
            // queue an envelope, or route it through vanilla error recovery.
            rejections++;
            return Delivery.REJECTED_SUPERSEDED;
        }
        if (custodyId != null) custody.ready(custodyId);
        RegionRoute route = directory.resolve(request.chunkKey()).orElse(null);
        if (route == null) {
            rejections++;
            if (custodyId != null) custody.cancel(custodyId);
            return Delivery.REJECTED_UNKNOWN_CHUNK;
        }
        boolean staleTopology = !route.generation().equals(request.requestedTopologyGeneration());
        RegionTaskEnvelope envelope = new RegionTaskEnvelope("chunk-completion-" + request.completionKind(),
            new TaskPayload.ChunkCompletion(request.chunkKey().toString(), request.requestGeneration(), request.completionKind(), request.correlationId()), request.chunkKey(),
            route.generation(), route.ownerEpoch(), "async-chunk-producer", request.correlationId(), new TaskSequence(request.requestGeneration()), AdmissionPolicy.REROUTE);
        OwnerGateway.Delivery delivery = gateway.submit(envelope);
        if (delivery != OwnerGateway.Delivery.QUEUED && delivery != OwnerGateway.Delivery.REROUTED && delivery != OwnerGateway.Delivery.DEFERRED) {
            rejections++;
            if (custodyId != null) custody.cancel(custodyId);
            return Delivery.REJECTED_GATEWAY;
        }
        if (custodyId != null) custody.queued(custodyId);
        if (staleTopology) { reroutes++; return Delivery.REROUTED_CURRENT_OWNER; }
        return Delivery.QUEUED;
    }
    public long reroutes() { return reroutes; }
    public long rejections() { return rejections; }
    public enum Delivery { QUEUED, REROUTED_CURRENT_OWNER, REJECTED_UNKNOWN_CHUNK, REJECTED_SUPERSEDED, REJECTED_GATEWAY }
}
