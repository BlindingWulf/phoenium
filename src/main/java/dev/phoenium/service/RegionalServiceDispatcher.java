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

    public Handle submit(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(body, "body");
        if (!scheduler.lifecycle().acceptsSimulationWork())
            throw new IllegalStateException("regional service admission after scheduler stop");
        RegionRoute route = directory.resolve(key).orElseThrow(() -> new IllegalArgumentException("unknown regional service key " + key));
        long operationId = ++nextOperation;
        long sequence = ++nextSequence;
        Pending retained = new Pending(key, kind, body);
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
        return new Handle(serviceId, worldInstance, operationId, kind);
    }

    /** Drives the existing serial scheduler until this exact operation commits or the barrier trips. */
    public void drain(Handle handle) {
        requireHandle(handle);
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("cannot drain regional service while owner authority is active");
        while (pending.containsKey(handle.operationId())) {
            long before = scheduler.advances();
            scheduler.advanceOne();
            if (!pending.containsKey(handle.operationId())) break;
            if (scheduler.failureBarrier().tripped()) {
                Pending failed = pending.remove(handle.operationId());
                Throwable cause = scheduler.failureBarrier().record().map(record -> record.cause()).orElse(null);
                throw new RegionalServiceFailure("regional service failed " + handle, cause, failed);
            }
            if (scheduler.advances() == before)
                throw new IllegalStateException("regional service cannot progress at current topology boundary: " + handle);
        }
    }

    public void executeAndDrain(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body) {
        drain(submit(key, kind, body));
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.RegionalService operation) throws Exception {
        if (!serviceId.equals(operation.serviceId()) || worldInstance != operation.worldInstance())
            throw new IllegalStateException("foreign regional service identity " + operation);
        Pending retained = pending.get(operation.operationId());
        if (retained == null) throw new IllegalStateException("missing or repeated regional service operation " + operation.operationId());
        if (!retained.kind().name().equals(operation.operationKind()))
            throw new IllegalStateException("regional service operation kind changed in transit");
        RegionRoute current = directory.resolve(retained.key()).orElseThrow(() -> new IllegalStateException("regional service key became unresolved"));
        if (!current.region().equals(runtime.id()))
            throw new IllegalStateException("regional service reached foreign owner " + runtime.id() + " expected " + current.region());
        try {
            retained.body().run(new Execution(serviceId, worldInstance, operation.operationId(), retained.kind(), retained.key(), runtime));
        } catch (Exception | Error failure) {
            throw failure;
        }
        if (pending.remove(operation.operationId()) != retained)
            throw new IllegalStateException("regional service operation identity changed during execution");
    }

    public int pendingCount() { return pending.size(); }
    public long worldInstance() { return worldInstance; }
    public String serviceId() { return serviceId; }

    /** Stop admission cleanup; retained bodies are released without executing them from the caller. */
    public int abandonPending() {
        int abandoned = pending.size();
        pending.clear();
        return abandoned;
    }

    private void requireHandle(Handle handle) {
        Objects.requireNonNull(handle, "handle");
        if (!serviceId.equals(handle.serviceId()) || worldInstance != handle.worldInstance())
            throw new IllegalArgumentException("foreign regional service handle " + handle);
        Pending retained = pending.get(handle.operationId());
        if (retained != null && retained.kind() != handle.kind())
            throw new IllegalArgumentException("regional service handle kind mismatch");
    }
    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }

    private record Pending(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body) { }
    public record Handle(String serviceId, long worldInstance, long operationId, RegionalServiceKind kind) { }
    public record Execution(String serviceId, long worldInstance, long operationId, RegionalServiceKind kind,
                            RoutingKey key, RegionRuntime runtime) { }

    @FunctionalInterface
    public interface RegionalServiceWork {
        void run(Execution execution) throws Exception;
    }

    public static final class RegionalServiceFailure extends IllegalStateException {
        private final RegionalServiceKind kind;
        private RegionalServiceFailure(String message, Throwable cause, Pending failed) {
            super(message, cause);
            this.kind = failed == null ? null : failed.kind();
        }
        public RegionalServiceKind kind() { return kind; }
    }
}
