package dev.phoenium.topology;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import java.util.Map;
import java.util.Objects;
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
    private Convergence pendingConvergence;
    private Runnable publicationWindowProbe;

    /**
     * Deterministic, non-blocking seam invoked under the metadata gate between the directory
     * publication and the derived object-ledger reconciliation. Test-only: a probe may inspect the
     * committed window in which no object has an owner, but it must never block or re-enter the
     * coordinator.
     */
    public void setPublicationWindowProbe(Runnable probe) { this.publicationWindowProbe = probe; }

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
            if (pendingConvergence != null) throw new IllegalStateException("topology admission closed while convergence is pending: " + pendingConvergence.reason());
            transition.participants().forEach(id -> runtime(id).beginTransition());
            pending = transition;
        } finally { metadataGate.unlock(); }
    }
    /**
     * Records incompatible candidate closures as pending convergence. The published directory is
     * left untouched, so every participant's frozen domain is retained; ordinary admission for the
     * participants is closed (beginTransition) and conflicting transition requests are denied. The
     * converged proposal commits only at a later quiescent boundary through the same publication
     * steps as an ordinary transition.
     */
    public void recordConvergence(Set<RoutingKey> conflictedKeys, Transition proposal, String reason) {
        metadataGate.lock();
        try {
            if (pending != null) throw new IllegalStateException("topology transition already pending");
            if (pendingConvergence != null) throw new IllegalStateException("convergence already pending");
            proposal.participants().forEach(id -> runtime(id).beginTransition());
            pendingConvergence = new Convergence(conflictedKeys, proposal, Objects.requireNonNull(reason, "reason"));
        } finally { metadataGate.unlock(); }
    }
    /** Called by the scheduler's event loop. It never blocks on a region. */
    public boolean advance() {
        metadataGate.lock();
        try {
            if (pending == null && pendingConvergence == null) return false;
            Transition transition = pending != null ? pending : pendingConvergence.proposal();
            if (transition.participants().stream().map(this::runtime).anyMatch(runtime -> runtime.leases().hasActiveLease())) return false;
            TopologyGeneration next = directory.snapshot().generation().next();
            Map<RoutingKey, RegionRoute> routes = transition.owners().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> new RegionRoute(entry.getValue(), next, runtime(entry.getValue()).ownerEpoch().next())));
            directory.publish(next, routes);
            // Publication window: the directory already commits the new generation while the
            // derived object ledger still names the old owner. ReconcileTopology runs only after
            // this seam, so inside it no object has any owner (stale A = 0, premature B = 0).
            if (publicationWindowProbe != null) publicationWindowProbe.run();
            if (objects != null) objects.reconcileTopology();
            transition.participants().forEach(id -> runtime(id).finishTransition(runtime(id).ownerEpoch().next()));
            transition.participants().forEach(id -> gateway.releaseTransitionMailbox(runtime(id)));
            pending = null;
            pendingConvergence = null;
            return true;
        } finally { metadataGate.unlock(); }
    }
    public boolean hasPendingTransition() { return pending != null; }
    public boolean hasPendingConvergence() { return pendingConvergence != null; }
    public Convergence convergence() { return pendingConvergence; }
    public int metadataGateHoldCount() { return metadataGate.getHoldCount(); }
    private RegionRuntime runtime(dev.phoenium.region.RegionId id) {
        RegionRuntime runtime = runtimes.get(id);
        if (runtime == null) throw new IllegalArgumentException("unknown transition participant " + id);
        return runtime;
    }
    public record Transition(Set<dev.phoenium.region.RegionId> participants, Map<RoutingKey, dev.phoenium.region.RegionId> owners) {
        public Transition { participants = Set.copyOf(participants); owners = Map.copyOf(owners); }
    }
    /** Incompatible closures recorded for later quiescent convergence; the old domains stay frozen. */
    public record Convergence(Set<RoutingKey> conflictedKeys, Transition proposal, String reason) {
        public Convergence { conflictedKeys = Set.copyOf(conflictedKeys); }
    }
}
