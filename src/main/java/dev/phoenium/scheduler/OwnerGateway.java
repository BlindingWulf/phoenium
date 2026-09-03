package dev.phoenium.scheduler;

import dev.phoenium.region.RegionRuntime;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import java.util.Map;

/** The only Stage 1 generic path from an envelope to an owner inbox. */
public final class OwnerGateway {
    private final RegionDirectory directory;
    private final Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes;
    private final RegionScheduler scheduler;

    public OwnerGateway(RegionDirectory directory, Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, RegionScheduler scheduler) {
        this.directory = directory; this.runtimes = runtimes; this.scheduler = scheduler;
    }
    public Delivery submit(RegionTaskEnvelope envelope) {
        if (!scheduler.lifecycle().acceptsSimulationWork()) return Delivery.REJECTED_SCHEDULER_STOPPED;
        RegionRoute route = directory.resolve(envelope.routingKey()).orElseThrow(() -> new IllegalArgumentException("unknown routing key " + envelope.routingKey()));
        boolean stale = !route.generation().equals(envelope.expectedGeneration()) || !route.ownerEpoch().equals(envelope.expectedOwnerEpoch());
        if (stale && envelope.admissionPolicy() == AdmissionPolicy.REJECT) return Delivery.REJECTED_STALE;
        RegionTaskEnvelope current = stale ? envelope.rerouted(route.generation(), route.ownerEpoch()) : envelope;
        RegionRuntime runtime = runtimes.get(route.region());
        if (runtime == null) return Delivery.REJECTED_STALE;
        if (runtime.transitioning()) {
            if (current.admissionPolicy() == AdmissionPolicy.REJECT) return Delivery.REJECTED_TRANSITION;
            runtime.defer(current);
            return Delivery.DEFERRED;
        }
        runtime.enqueue(current);
        return stale ? Delivery.REROUTED : Delivery.QUEUED;
    }
    public enum Delivery { QUEUED, REROUTED, DEFERRED, REJECTED_STALE, REJECTED_TRANSITION, REJECTED_SCHEDULER_STOPPED }

    /** A committed mailbox is always sent back through current directory resolution. */
    public void releaseTransitionMailbox(RegionRuntime runtime) {
        RegionTaskEnvelope envelope;
        while ((envelope = runtime.pollTransitionMailbox().orElse(null)) != null) {
            submit(envelope);
        }
    }
}
