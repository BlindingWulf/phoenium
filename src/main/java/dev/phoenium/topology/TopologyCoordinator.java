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
    private RegionDirectory.PendingChange directoryChange;
    private Runnable publicationWindowProbe;
    private RegionalStateMigration stateMigration;
    private boolean migrationInProgress;
    private boolean migrationFailed;
    private boolean admissionClosing;

    /**
     * Deterministic, non-blocking seam invoked under the metadata gate between the directory
     * publication and the derived object-ledger reconciliation. Test-only: a probe may inspect the
     * committed window in which no object has an owner, but it must never block or re-enter the
     * coordinator.
     */
    public void setPublicationWindowProbe(Runnable probe) { this.publicationWindowProbe = probe; }

    /** Installs live regional state for an enclosure constructed during pre-start FULL bootstrap. */
    public void installStateMigration(RegionalStateMigration migration) {
        Objects.requireNonNull(migration, "migration");
        metadataGate.lock();
        try {
            if (stateMigration == migration) return;
            if (stateMigration != RegionalStateMigration.NONE)
                throw new IllegalStateException("regional topology migration installed twice");
            if (pending != null || pendingConvergence != null || directoryChange != null
                    || admissionClosing || migrationInProgress || migrationFailed)
                throw new IllegalStateException("regional topology migration installed during transition");
            stateMigration = migration;
        } finally { metadataGate.unlock(); }
    }

    public TopologyCoordinator(RegionDirectory directory, Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, OwnerGateway gateway) {
        this(directory, runtimes, gateway, null, RegionalStateMigration.NONE);
    }

    /** A non-null registry is reconciled at the quiescent boundary so derived object bindings follow the published topology exactly once. */
    public TopologyCoordinator(RegionDirectory directory, Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, OwnerGateway gateway, dev.phoenium.ownership.ObjectOwnershipRegistry objects) {
        this(directory, runtimes, gateway, objects, RegionalStateMigration.NONE);
    }
    public TopologyCoordinator(RegionDirectory directory, Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes,
                               OwnerGateway gateway, dev.phoenium.ownership.ObjectOwnershipRegistry objects,
                               RegionalStateMigration stateMigration) {
        this.directory = directory; this.runtimes = runtimes; this.gateway = gateway; this.objects = objects;
        this.stateMigration = Objects.requireNonNull(stateMigration, "stateMigration");
    }
    public void request(Transition transition) {
        metadataGate.lock();
        try {
            if (pending != null) throw new IllegalStateException("topology transition already pending");
            if (pendingConvergence != null) throw new IllegalStateException("topology admission closed while convergence is pending: " + pendingConvergence.reason());
            pending = transition;
        } finally { metadataGate.unlock(); }
        tryCloseAdmission();
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
            pendingConvergence = new Convergence(conflictedKeys, proposal, Objects.requireNonNull(reason, "reason"));
        } finally { metadataGate.unlock(); }
        tryCloseAdmission();
    }
    /** Called by the scheduler's event loop. It never blocks on a region. */
    public boolean advance() {
        if (!tryCloseAdmission()) return false;
        RegionDirectory.PendingChange completed = null;
        Transition transition;
        RegionDirectory.View proposed;
        metadataGate.lock();
        try {
            if (pending == null && pendingConvergence == null) return false;
            if (migrationFailed) return false;
            if (migrationInProgress) return false;
            transition = pending != null ? pending : pendingConvergence.proposal();
            if (transition.participants().stream().map(this::runtime).anyMatch(runtime -> runtime.leases().hasActiveLease())) return false;
            TopologyGeneration next = directory.snapshot().generation().next();
            Map<RoutingKey, RegionRoute> routes = transition.owners().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> new RegionRoute(entry.getValue(), next,
                        transition.participants().contains(entry.getValue()) ? runtime(entry.getValue()).ownerEpoch().next()
                                : runtime(entry.getValue()).ownerEpoch())));
            Map<dev.phoenium.core.WorldId, RegionRoute> defaults = new java.util.LinkedHashMap<>();
            directory.snapshot().worldDefaults().forEach((world, route) -> defaults.put(world,
                    new RegionRoute(route.region(), next, transition.participants().contains(route.region())
                            ? runtime(route.region()).ownerEpoch().next() : route.ownerEpoch())));
            proposed = new RegionDirectory.View(next, routes, defaults);
            migrationInProgress = true;
        } finally { metadataGate.unlock(); }

        RegionalStateMigration.Prepared prepared;
        try {
            prepared = stateMigration.prepare(proposed);
        } catch (Throwable failure) {
            failMigration(failure, transition);
            throw failure;
        }

        metadataGate.lock();
        try {
            if (transition.participants().stream().map(this::runtime).anyMatch(runtime -> runtime.leases().hasActiveLease()))
                throw new IllegalStateException("participant lease appeared while topology admission was closed");
            proposed = directory.publishForTransition(proposed.generation(), proposed.routes(), proposed.worldDefaults());
        } catch (Throwable failure) {
            failMigration(failure, transition);
            throw failure;
        } finally { metadataGate.unlock(); }

        try {
            directory.signalPublication(proposed);
            // Publication window: the directory already commits the new generation while the
            // derived object ledger still names the old owner. No coordinator metadata gate is
            // held across this test callback or object-ledger reconciliation.
            if (publicationWindowProbe != null) publicationWindowProbe.run();
            if (objects != null) objects.reconcileTopology();
            RegionDirectory.View published = directory.snapshot();
            if (!published.equals(proposed)) throw new IllegalStateException("published topology view differs from prepared migration");
            prepared.install(published);
        } catch (Throwable failure) {
            failMigration(failure, transition);
            throw failure;
        }

        try {
            if (!directory.snapshot().equals(proposed))
                throw new IllegalStateException("topology changed before regional state install acknowledgement");
            for (var id : transition.participants()) {
                RegionRuntime runtime = runtime(id);
                runtime.finishTransition(runtime.ownerEpoch().next());
            }
        } catch (Throwable failure) {
            failMigration(failure, transition);
            throw failure;
        }

        metadataGate.lock();
        try {
            pending = null;
            pendingConvergence = null;
            completed = directoryChange;
            directory.finishChange(completed);
            directoryChange = null;
            migrationInProgress = false;
            migrationFailed = false;
        } finally {
            metadataGate.unlock();
        }
        for (var id : transition.participants()) gateway.releaseTransitionMailbox(runtime(id));
        directory.signalChange(completed);
        return true;
    }
    private boolean tryCloseAdmission() {
        Transition transition;
        metadataGate.lock();
        try {
            if (migrationFailed || migrationInProgress) return false;
            if (directoryChange != null) return true;
            if (admissionClosing) return false;
            transition = pending != null ? pending : pendingConvergence != null ? pendingConvergence.proposal() : null;
            if (transition == null) return false;
            admissionClosing = true;
        } finally { metadataGate.unlock(); }

        RegionDirectory.PendingChange change;
        try {
            change = directory.beginChange(transition.owners().keySet(), transition.participants());
        } catch (Throwable failure) {
            metadataGate.lock(); try { admissionClosing = false; } finally { metadataGate.unlock(); }
            failMigration(failure, transition);
            throw failure;
        }
        if (change == null) {
            metadataGate.lock(); try { admissionClosing = false; } finally { metadataGate.unlock(); }
            return false;
        }
        try {
            transition.participants().forEach(id -> runtime(id).beginTransition());
        } catch (Throwable failure) {
            metadataGate.lock(); try { admissionClosing = false; } finally { metadataGate.unlock(); }
            failMigration(failure, transition);
            throw failure;
        }
        metadataGate.lock();
        try {
            if ((pending != transition && (pendingConvergence == null || pendingConvergence.proposal() != transition)) || directoryChange != null)
                throw new IllegalStateException("topology proposal changed while admission closure was prepared");
            directoryChange = change;
            admissionClosing = false;
        } finally { metadataGate.unlock(); }
        directory.signalChangeStarted(change);
        return true;
    }
    private void failMigration(Throwable failure, Transition transition) {
        metadataGate.lock();
        try {
            migrationInProgress = false;
            migrationFailed = true;
            dev.phoenium.core.OwnerEpoch epoch = transition.participants().stream().findFirst()
                .map(this::runtime).map(RegionRuntime::ownerEpoch).orElse(new OwnerEpoch(0));
            gateway.scheduler().failureBarrier().trip(failure, directory.snapshot().generation(), epoch, null);
            // pending transition and directory change deliberately remain attached. Admission is
            // never reopened after an incomplete preflight, publication, or state install.
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
