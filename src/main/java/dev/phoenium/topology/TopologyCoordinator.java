package dev.phoenium.topology;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** The sole lock is a short-lived metadata gate. Workers never wait under it. */
public final class TopologyCoordinator {
    private final RegionDirectory directory;
    private final Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes;
    private final OwnerGateway gateway;
    private final dev.phoenium.ownership.ObjectOwnershipRegistry objects;
    private final ReentrantLock metadataGate = new ReentrantLock();
    private Transition pending;

    public TopologyCoordinator(RegionDirectory directory, Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, OwnerGateway gateway) {
        this(directory, runtimes, gateway, null);
    }

    /** A non-null registry is reconciled at the quiescent boundary so derived object bindings follow the published topology exactly once. */
    public TopologyCoordinator(RegionDirectory directory, Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, OwnerGateway gateway, dev.phoenium.ownership.ObjectOwnershipRegistry objects) {
        this.directory = directory; this.runtimes = runtimes; this.gateway = gateway; this.objects = objects;
    }
    public void request(Transition transition) {
        metadataGate.lock();
        try {
            if (pending != null) throw new IllegalStateException("topology transition already pending");
            transition.participants().forEach(id -> runtime(id).beginTransition());
            pending = transition;
        } finally { metadataGate.unlock(); }
    }
    /** Called by the scheduler's event loop. It never blocks on a region. */
    public boolean advance() {
        metadataGate.lock();
        try {
            if (pending == null) return false;
            if (pending.participants().stream().map(this::runtime).anyMatch(runtime -> runtime.leases().hasActiveLease())) return false;
            TopologyGeneration next = directory.snapshot().generation().next();
            Map<RoutingKey, RegionRoute> routes = pending.owners().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> new RegionRoute(entry.getValue(), next, runtime(entry.getValue()).ownerEpoch().next())));
            directory.publish(next, routes);
            if (objects != null) objects.reconcileTopology();
            pending.participants().forEach(id -> runtime(id).finishTransition(runtime(id).ownerEpoch().next()));
            pending.participants().forEach(id -> gateway.releaseTransitionMailbox(runtime(id)));
            pending = null;
            return true;
        } finally { metadataGate.unlock(); }
    }
    public boolean hasPendingTransition() { return pending != null; }
    public int metadataGateHoldCount() { return metadataGate.getHoldCount(); }
    private RegionRuntime runtime(dev.phoenium.region.RegionId id) {
        RegionRuntime runtime = runtimes.get(id);
        if (runtime == null) throw new IllegalArgumentException("unknown transition participant " + id);
        return runtime;
    }
    public record Transition(Set<dev.phoenium.region.RegionId> participants, Map<RoutingKey, dev.phoenium.region.RegionId> owners) {
        public Transition { participants = Set.copyOf(participants); owners = Map.copyOf(owners); }
    }
}
