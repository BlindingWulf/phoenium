package dev.phoenium.service;

import dev.phoenium.ownership.OwnershipContext;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The one synchronous bridge from original Minecraft service calls to leased regional execution.
 * Only immutable operation identity is queued. A live body stays here until its exact typed
 * identity reaches the current owner; migration is therefore a normal gateway reroute rather
 * than closure transfer or replay.
 *
 * <p>{@link #drain(Handle)} may be used only at an owner-free original boundary. It never suspends
 * active authority and it never invokes a retained body itself: the existing one-worker scheduler
 * acquires/releases the lease and trips the common failure barrier.</p>
 */
public final class RegionalServiceDispatcher implements RegionalServiceExecutor {
    private final String serviceId;
    private final long worldInstance;
    private final RegionDirectory directory;
    private final Map<RegionId, RegionRuntime> runtimes;
    private final OwnerGateway gateway;
    private final RegionScheduler scheduler;
    private final Map<Long, Pending> pending = new LinkedHashMap<>();
    private long nextOperation;
    private long nextSequence;
    private BooleanSupplier boundaryAdvancer = () -> false;
    private Predicate<Set<RoutingKey>> closureEncloser;

    public RegionalServiceDispatcher(String serviceId, long worldInstance, RegionDirectory directory,
                                     Map<RegionId, RegionRuntime> runtimes, OwnerGateway gateway,
                                     RegionScheduler scheduler) {
        this.serviceId = requireText(serviceId, "serviceId");
        if (worldInstance <= 0) throw new IllegalArgumentException("worldInstance must be positive");
        this.worldInstance = worldInstance;
        this.directory = Objects.requireNonNull(directory, "directory");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        scheduler.installRegionalServiceExecutor(this);
    }

    /** Installs the live topology coordinator's owner-free, non-blocking boundary advance. */
    public void installBoundaryAdvancer(BooleanSupplier boundaryAdvancer) {
        this.boundaryAdvancer = Objects.requireNonNull(boundaryAdvancer, "boundaryAdvancer");
    }

    public void installClosureEncloser(Predicate<Set<RoutingKey>> closureEncloser) {
        if (this.closureEncloser != null && this.closureEncloser != closureEncloser)
            throw new IllegalStateException("regional service closure encloser already installed");
        this.closureEncloser = Objects.requireNonNull(closureEncloser, "closureEncloser");
    }

    /** Owner-free preflight that converges a finite chunk halo before any original callback runs. */
    public void ensureClosure(RoutingKey center, int radius) {
        if (radius < 0) throw new IllegalArgumentException("negative regional service closure radius");
        ensureClosure(new dev.phoenium.topology.TopologyPlanner().requiredClosure(Set.of(center), radius));
    }

    public void ensureClosure(Set<RoutingKey> keys) {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("regional service closure requested under owner authority");
        Set<RoutingKey> footprint = Set.copyOf(keys);
        if (footprint.isEmpty()) return;
        RoutingKey center = footprint.iterator().next();
        for (RoutingKey key : footprint) if (!key.world().equals(center.world()))
            throw new IllegalArgumentException("regional service closure crosses worlds");
        Predicate<Set<RoutingKey>> encloser = Objects.requireNonNull(closureEncloser,
            "regional service closure encloser is not installed");
        while (true) {
            if (scheduler.failureBarrier().tripped())
                throw new IllegalStateException("regional service closure failed at topology barrier");
            // The enclosure itself may publish a completed convergence and return false so
            // callers recapture routes. Count that publication as progress too: observing only
            // the following boundary advance incorrectly rejects an already finished merge.
            long generation = directory.snapshot().generation().value();
            long attempts = scheduler.deliveryAttempts();
            if (encloser.test(footprint)) break;
            boolean advanced = boundaryAdvancer.getAsBoolean();
            if (scheduler.failureBarrier().tripped())
                throw new IllegalStateException("regional service closure failed at topology barrier");
            if (directory.snapshot().generation().value() == generation
                    && scheduler.deliveryAttempts() == attempts && !advanced)
                throw new IllegalStateException("regional service closure cannot progress for " + center);
        }
        requireCommonOwner(center, footprint);
    }

    /** Revalidation for nested producer effects after the owner-free closure was admitted. */
    public void requireCurrentClosure(RoutingKey center, int radius) {
        requireCurrentClosure(new dev.phoenium.topology.TopologyPlanner().requiredClosure(Set.of(center), radius));
    }

    public void requireCurrentClosure(Set<RoutingKey> footprint) {
        if (footprint.isEmpty()) return;
        RoutingKey center = footprint.iterator().next();
        for (RoutingKey key : footprint) if (!key.world().equals(center.world()))
            throw new IllegalArgumentException("regional service closure crosses worlds");
        requireCurrentOwner(center);
        requireCommonOwner(center, footprint);
    }

    private void requireCommonOwner(RoutingKey center, Set<RoutingKey> footprint) {
        RegionRoute source = directory.resolve(center).orElseThrow(
            () -> new IllegalStateException("regional service closure source became unresolved"));
        for (RoutingKey key : footprint) {
            RegionRoute route = directory.resolve(key).orElseThrow(
                () -> new IllegalStateException("regional service closure key is unresolved " + key));
            if (!route.region().equals(source.region()) || !route.generation().equals(source.generation()))
                throw new IllegalStateException("regional service closure retained foreign owner " + key);
        }
    }

    public Handle submit(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(body, "body");
        if (!scheduler.acceptsSimulationWork())
            throw new IllegalStateException("regional service admission after scheduler stop");
        RegionRoute route = directory.resolve(key).orElseThrow(() -> new IllegalArgumentException("unknown regional service key " + key));
        long operationId = nextOperation = Math.incrementExact(nextOperation);
        long sequence = nextSequence = Math.incrementExact(nextSequence);
        Handle handle = new Handle(serviceId, worldInstance, operationId, kind);
        Pending retained = new Pending(key, kind, body, handle);
        pending.put(operationId, retained);
        TaskPayload.RegionalService payload = new TaskPayload.RegionalService(serviceId, worldInstance, operationId, kind.name());
        RegionTaskEnvelope envelope = new RegionTaskEnvelope("regional-service:" + kind.name(), payload, key,
            route.generation(), route.ownerEpoch(), serviceId, new TaskCorrelationId(sequence),
            new TaskSequence(sequence), AdmissionPolicy.REROUTE);
        OwnerGateway.Delivery delivery;
        try {
            delivery = gateway.submit(envelope);
        } catch (Throwable failure) {
            pending.remove(operationId);
            throw failure;
        }
        if (delivery != OwnerGateway.Delivery.QUEUED && delivery != OwnerGateway.Delivery.REROUTED
                && delivery != OwnerGateway.Delivery.DEFERRED) {
            pending.remove(operationId);
            throw new IllegalStateException("regional service admission rejected: " + delivery);
        }
        return handle;
    }

    /** Drives the existing serial scheduler until this exact operation commits or the barrier trips. */
    public void drain(Handle handle) {
        requireHandle(handle);
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("cannot drain regional service while owner authority is active");
        while (handle.state == Outcome.PENDING) {
            long before = scheduler.deliveryAttempts();
            scheduler.advanceOne();
            if (handle.state == Outcome.COMPLETE) return;
            if (handle.state == Outcome.FAILED) {
                pending.remove(handle.operationId());
                Throwable cause = handle.failure;
                // Preserve the original unchecked callback identity for vanilla callers and catch
                // sites. Only a checked callback failure needs a cause-preserving bridge because
                // the synchronous drain API itself has no checked throws clause.
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new RegionalServiceFailure("regional service failed " + handle, cause, handle.kind());
            }
            if (handle.state == Outcome.ABANDONED)
                throw new IllegalStateException("regional service was abandoned without acknowledgement: " + handle);
            if (scheduler.deliveryAttempts() == before && !boundaryAdvancer.getAsBoolean())
                throw new IllegalStateException("regional service cannot progress at current topology boundary: " + handle);
        }
    }

    public void executeAndDrain(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body) {
        drain(submit(key, kind, body));
    }

    /** Validates an original producer mutation that is already executing inside an owner body. */
    public void requireCurrentOwner(RoutingKey key) {
        Objects.requireNonNull(key, "key");
        var active = OwnershipContext.current().orElseThrow(
            () -> new IllegalStateException("regional service producer has no owner context"));
        RegionRoute current = directory.resolve(key).orElseThrow(
            () -> new IllegalStateException("regional service producer key became unresolved"));
        if (!current.region().equals(active.region()))
            throw new IllegalStateException("regional service producer reached foreign owner "
                + active.region() + " expected " + current.region());
        RegionRuntime runtime = Objects.requireNonNull(runtimes.get(active.region()),
            "regional service producer runtime");
        runtime.leases().requireCurrent(active.lease());
        if (!current.ownerEpoch().equals(runtime.ownerEpoch()))
            throw new IllegalStateException("regional service producer runtime owner epoch is stale");
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.RegionalService operation) throws Exception {
        if (!serviceId.equals(operation.serviceId()) || worldInstance != operation.worldInstance())
            throw new IllegalStateException("foreign regional service identity " + operation);
        Pending retained = pending.get(operation.operationId());
        if (retained == null) throw new IllegalStateException("missing or repeated regional service operation " + operation.operationId());
        var active = OwnershipContext.current().orElseThrow(() -> new IllegalStateException("regional service execution has no owner context"));
        if (!active.region().equals(runtime.id())) throw new IllegalStateException("regional service context names foreign owner");
        runtime.leases().requireCurrent(active.lease());
        if (!retained.kind().name().equals(operation.operationKind()))
            throw new IllegalStateException("regional service operation kind changed in transit");
        RegionRoute current = directory.resolve(retained.key()).orElseThrow(() -> new IllegalStateException("regional service key became unresolved"));
        if (!current.region().equals(runtime.id()))
            throw new IllegalStateException("regional service reached foreign owner " + runtime.id() + " expected " + current.region());
        if (!current.ownerEpoch().equals(runtime.ownerEpoch()))
            throw new IllegalStateException("regional service runtime owner epoch is stale");
        try {
            retained.body().run(new Execution(serviceId, worldInstance, operation.operationId(), retained.kind(), retained.key(), runtime));
        } catch (Exception | Error failure) {
            retained.handle().failed(failure);
            throw failure;
        }
        if (pending.remove(operation.operationId()) != retained)
            throw new IllegalStateException("regional service operation identity changed during execution");
        retained.handle().complete();
    }

    public int pendingCount() { return pending.size(); }
    public long worldInstance() { return worldInstance; }
    public String serviceId() { return serviceId; }

    /** Stop admission cleanup; retained bodies are released without executing them from the caller. */
    public int abandonPending() {
        int abandoned = pending.size();
        pending.values().forEach(value -> value.handle().abandoned());
        pending.clear();
        return abandoned;
    }

    private void requireHandle(Handle handle) {
        Objects.requireNonNull(handle, "handle");
        if (!serviceId.equals(handle.serviceId()) || worldInstance != handle.worldInstance())
            throw new IllegalArgumentException("foreign regional service handle " + handle);
        if (handle.owner != this) throw new IllegalArgumentException("foreign regional service handle instance");
    }
    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }

    private record Pending(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body, Handle handle) { }
    public final class Handle {
        private final RegionalServiceDispatcher owner = RegionalServiceDispatcher.this;
        private final String serviceId;
        private final long worldInstance;
        private final long operationId;
        private final RegionalServiceKind kind;
        private Outcome state = Outcome.PENDING;
        private Throwable failure;
        private Handle(String serviceId, long worldInstance, long operationId, RegionalServiceKind kind) {
            this.serviceId = serviceId; this.worldInstance = worldInstance; this.operationId = operationId; this.kind = kind;
        }
        public String serviceId() { return serviceId; }
        public long worldInstance() { return worldInstance; }
        public long operationId() { return operationId; }
        public RegionalServiceKind kind() { return kind; }
        public boolean completed() { return state == Outcome.COMPLETE; }
        public boolean failed() { return state == Outcome.FAILED; }
        private void complete() { if (state != Outcome.PENDING) throw new IllegalStateException("service handle already terminal"); state = Outcome.COMPLETE; }
        private void failed(Throwable failure) { this.failure = failure; state = Outcome.FAILED; }
        private void abandoned() { if (state == Outcome.PENDING) state = Outcome.ABANDONED; }
        @Override public String toString() { return serviceId + "/" + worldInstance + "/" + operationId + "/" + kind; }
    }
    private enum Outcome { PENDING, COMPLETE, FAILED, ABANDONED }
    public record Execution(String serviceId, long worldInstance, long operationId, RegionalServiceKind kind,
                            RoutingKey key, RegionRuntime runtime) { }

    @FunctionalInterface
    public interface RegionalServiceWork {
        void run(Execution execution) throws Exception;
    }

    public static final class RegionalServiceFailure extends IllegalStateException {
        private final RegionalServiceKind kind;
        private RegionalServiceFailure(String message, Throwable cause, RegionalServiceKind failedKind) {
            super(message, cause);
            this.kind = failedKind;
        }
        public RegionalServiceKind kind() { return kind; }
    }
}
