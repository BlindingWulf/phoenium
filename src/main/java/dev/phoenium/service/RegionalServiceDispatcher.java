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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    private final Map<Long, Pending> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private long nextOperation;
    private long nextSequence;
    private BooleanSupplier boundaryAdvancer = () -> false;
    private Predicate<Set<RoutingKey>> closureEncloser;
    private NeighborCascadeContinuation.Environment neighborCascades;

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

    /** Installs the native neighbour-cascade continuation for the plain owned service kinds. */
    public void installNeighborCascades(NeighborCascadeContinuation.Environment environment) {
        if (neighborCascades != null && neighborCascades != environment)
            throw new IllegalStateException("neighbor cascade continuation already installed");
        neighborCascades = Objects.requireNonNull(environment, "environment");
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

    /** S9-D7: ensureClosure for a footprint whose every key names center's world, decided without the footprint
     * while that world is uniformly routed (RegionDirectory.uniformRoute): the same owner-context, barrier and
     * encloser checks run, and every key would resolve to the one route. Returns false, having admitted nothing,
     * when the world is not uniformly routed or the encloser declines; the caller then runs ensureClosure. */
    public boolean ensureUniformClosure(RoutingKey center) {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("regional service closure requested under owner authority");
        if (directory.uniformRoute(center.world()) == null) return false;
        Predicate<Set<RoutingKey>> encloser = Objects.requireNonNull(closureEncloser,
            "regional service closure encloser is not installed");
        if (scheduler.failureBarrier().tripped())
            throw new IllegalStateException("regional service closure failed at topology barrier");
        return encloser.test(Set.of(center)) && directory.uniformRoute(center.world()) != null;
    }

    /** S9-D7: requireCurrentClosure for a footprint whose every key names center's world, decided without the
     * footprint while that world is uniformly routed; any key's owner check is then center's. Returns false,
     * having checked nothing, when the world is not uniformly routed; the caller then runs requireCurrentClosure. */
    public boolean requireUniformClosure(RoutingKey center) {
        if (directory.uniformRoute(center.world()) == null) return false;
        requireCurrentOwner(center);
        return true;
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
        // S9-D7: both callers already required every key to name center's world; in a uniformly routed world
        // the source and each key resolve to the same route.
        if (directory.uniformRoute(center.world()) != null) return;
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
        if (kind == RegionalServiceKind.LOCAL_BLOCK_ENTITY_TICK)
            throw new IllegalArgumentException("local worker capability cannot be obtained through generic submission");
        return submit(key, kind, body, false, false);
    }

    /**
     * The C2ME persisted-read continuation is allowed to observe completion only after the
     * regional lease used by its deferred POI body has been released.  This is deliberately a
     * purpose-specific API: ordinary regional services retain their existing completion point.
     */
    public Handle submitC2mePersistedPoiAfterRelease(RoutingKey key, RegionalServiceWork body) {
        return submit(key, RegionalServiceKind.C2ME_PERSISTED_POI_RECONCILE, body, false, true);
    }

    private Handle submit(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body,
                          boolean local, boolean completeAfterRelease) {
        return submit(key, kind, body, local, ignored -> true, completeAfterRelease);
    }
    private Handle submit(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body, boolean local,
                          LocalPreparation preparation) {
        return submit(key, kind, body, local, preparation, false);
    }
    private Handle submit(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body, boolean local,
                          LocalPreparation preparation, boolean completeAfterRelease) {
        Handle handle = scheduler.admit(() -> submitAdmitted(key, kind, body, local, preparation, completeAfterRelease), null);
        if (handle == null) throw new IllegalStateException("regional service admission after scheduler stop");
        return handle;
    }
    private Handle submitAdmitted(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body, boolean local,
                                  LocalPreparation preparation, boolean completeAfterRelease) {
        return admitted(key, kind, body, local, preparation, completeAfterRelease, false).handle;
    }
    /** An admitted operation: its handle and, for a direct turn, the never-enqueued envelope and its owner runtime. */
    private record Admitted(Handle handle, RegionTaskEnvelope envelope, RegionRuntime runtime) { }
    /** S9-D10: the envelope origin per kind, built once rather than concatenated per operation. */
    private static final String[] ORIGINS = java.util.Arrays.stream(RegionalServiceKind.values())
        .map(kind -> "regional-service:" + kind.name()).toArray(String[]::new);
    private Admitted admitted(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body, boolean local,
                              LocalPreparation preparation, boolean completeAfterRelease, boolean direct) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(body, "body");
        if (!scheduler.acceptsSimulationWork())
            throw new IllegalStateException("regional service admission after scheduler stop");
        RegionRoute route = directory.resolve(key).orElseThrow(() -> new IllegalArgumentException("unknown regional service key " + key));
        long operationId = nextOperation = Math.incrementExact(nextOperation);
        long sequence = nextSequence = Math.incrementExact(nextSequence);
        Handle handle = new Handle(serviceId, worldInstance, operationId, kind);
        Pending retained = new Pending(key, kind, body, handle, local, System.nanoTime(), preparation, completeAfterRelease);
        pending.put(operationId, retained);
        TaskPayload.RegionalService payload = new TaskPayload.RegionalService(serviceId, worldInstance, operationId, kind.name());
        RegionTaskEnvelope envelope = new RegionTaskEnvelope(ORIGINS[kind.ordinal()], payload, key,
            route.generation(), route.ownerEpoch(), serviceId, new TaskCorrelationId(sequence),
            new TaskSequence(sequence), AdmissionPolicy.REROUTE);
        // S9-D10: the gateway would queue this envelope, unchanged, on exactly this runtime (current route, so never
        // stale; no transition and no pending change for the key). Leave it unqueued for the caller's direct turn.
        if (direct) {
            RegionRuntime runtime = runtimes.get(route.region());
            if (runtime != null && !runtime.transitioning() && directory.pendingChange(key).isEmpty())
                return new Admitted(handle, envelope, runtime);
        }
        deliver(operationId, envelope);
        return new Admitted(handle, envelope, null);
    }
    private void deliver(long operationId, RegionTaskEnvelope envelope) {
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
    }
    /**
     * S9-D10: {@code submit} for {@link #executeAndDrain}, whose drain follows at once. When every inbox is empty the
     * drain's first advanceOne would select exactly this envelope, so the scheduler runs that turn directly
     * ({@link RegionScheduler#runImmediate}); otherwise, or when it declines, the envelope is delivered through the
     * gateway as before and the drain selects it in order. An owner caller keeps the queued path, so the drain still
     * refuses it.
     */
    private Handle submitForDrain(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body) {
        if (kind == RegionalServiceKind.LOCAL_BLOCK_ENTITY_TICK)
            throw new IllegalArgumentException("local worker capability cannot be obtained through generic submission");
        if (OwnershipContext.current().isPresent()) return submit(key, kind, body);
        Admitted admitted = scheduler.admit(() -> admitted(key, kind, body, false, ignored -> true, false, true), null);
        if (admitted == null) throw new IllegalStateException("regional service admission after scheduler stop");
        if (admitted.runtime != null && !scheduler.runImmediate(admitted.runtime, admitted.envelope))
            deliver(admitted.handle.operationId(), admitted.envelope);
        return admitted.handle;
    }

    /** Drives the existing serial scheduler until this exact operation commits or the barrier trips. */
    public void drain(Handle handle) {
        requireHandle(handle);
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("cannot drain regional service while owner authority is active");
        while (handle.state == Outcome.PENDING) {
            long before = scheduler.deliveryAttempts();
            scheduler.advanceOne();
            if (settled(handle)) return;
            var failed = scheduler.failureBarrier().record().orElse(null);
            if (failed != null)
                throw new IllegalStateException("regional service stopped by prior owner failure: " + handle, failed.cause());
            if (scheduler.deliveryAttempts() == before && !boundaryAdvancer.getAsBoolean())
                throw new IllegalStateException("regional service cannot progress at current topology boundary: " + handle
                    + " key=" + pending.get(handle.operationId()).key() + " runtimes="
                    + runtimes.values().stream().map(runtime -> runtime.id() + ":" + runtime.lifecycle()
                        + ":transition=" + runtime.transitioning() + ":inbox=" + runtime.inboxSize()
                        + ":leased=" + runtime.leases().hasActiveLease()).toList());
        }
    }

    /** The drain's report after the turn that ran {@code handle}: true when it completed; its failure or abandonment
     * is thrown; false while it is still pending. */
    private boolean settled(Handle handle) {
        if (handle.state == Outcome.COMPLETE) return true;
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
        return false;
    }
    /** S9-D10: a direct turn has already run (and settled) the handle, which the drain loop would report only after
     * the advanceOne that ran it; report it the same way. A handle still pending drains as before. */
    private void drainSubmitted(Handle handle) {
        if (handle.state != Outcome.PENDING && settled(handle)) return;
        drain(handle);
    }

    public void executeAndDrain(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body) {
        var cascade = NeighborCascadeContinuation.arm(neighborCascades, key, kind);
        if (cascade == null) { drainSubmitted(submitForDrain(key, kind, body)); return; }
        // A cut cascade waits only here, after the owned body committed and released its lease.
        try (cascade) {
            drain(submit(key, kind, cascade.wrap(body)));
            cascade.finish(this);
        }
    }

    public void requireNaturalSpawnOwnerFree() {
        if (OwnershipContext.current().isPresent() || scheduler.batchActive()
                || runtimes.values().stream().anyMatch(runtime -> runtime.leases().hasClaimOrSelection()))
            throw new IllegalStateException("natural-spawn wait retained a regional claim");
    }

    public boolean naturalSpawnOpen() { return scheduler.acceptsSimulationWork(); }

    /** Direct delivery of the one suffix whose native wait has returned. No ordinary
     * inbox round is allowed between its return reservation and its current owner. */
    public void resumeNaturalSpawn(ManagedBlockPollTurnFence.Reservation reservation,
                                   RoutingKey key, RegionalServiceWork body) {
        resumeReserved(reservation, key, RegionalServiceKind.CHUNK_GAMEPLAY, "natural-spawn-resume", body);
    }

    /** The same return fence, scoped to the retained Vault block-entity suffix. */
    public void resumeVaultTick(ManagedBlockPollTurnFence.Reservation reservation,
                               RoutingKey key, RegionalServiceWork body) {
        resumeReserved(reservation, key, RegionalServiceKind.BLOCK_ENTITY_TICK, "vault-tick-resume", body);
    }

    /** The same return fence, scoped to a retained native neighbour-update cascade. */
    void resumeNeighborCascade(ManagedBlockPollTurnFence.Reservation reservation,
                               RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body) {
        if (!NeighborCascadeContinuation.armable(kind)) throw new IllegalArgumentException("not a neighbor cascade kind: " + kind);
        resumeReserved(reservation, key, kind, "neighbor-cascade-resume", body);
    }

    private void resumeReserved(ManagedBlockPollTurnFence.Reservation reservation,
                               RoutingKey key, RegionalServiceKind kind, String name, RegionalServiceWork body) {
        reservation.requireResumeClaim();
        if (!scheduler.acceptsSimulationWork()) { reservation.retire(ManagedBlockPollTurnFence.State.SHUTDOWN); return; }
        RegionRoute route = directory.resolve(key).orElse(null);
        if (route == null || directory.pendingChange(key).filter(change -> !change.completed()).isPresent()) {
            reservation.retire(ManagedBlockPollTurnFence.State.STALE); return;
        }
        long operationId = nextOperation = Math.incrementExact(nextOperation);
        long sequence = nextSequence = Math.incrementExact(nextSequence);
        Handle handle = new Handle(serviceId, worldInstance, operationId, kind);
        var retained = new Pending(key, kind, execution -> {
            reservation.beginResuming();
            if (reservation.state() == ManagedBlockPollTurnFence.State.RESUMING) body.run(execution);
        }, handle, false, System.nanoTime(), ignored -> true, false);
        pending.put(operationId, retained);
        var payload = new TaskPayload.RegionalService(serviceId, worldInstance, operationId, kind.name());
        var envelope = new RegionTaskEnvelope(name, payload, key, route.generation(),
            route.ownerEpoch(), serviceId, new TaskCorrelationId(sequence), new TaskSequence(sequence), AdmissionPolicy.REJECT);
        try {
            if (!scheduler.resumeNaturalSpawn(reservation, envelope)) {
                reservation.retire(scheduler.acceptsSimulationWork() ? ManagedBlockPollTurnFence.State.STALE
                    : ManagedBlockPollTurnFence.State.SHUTDOWN);
                return;
            }
            if (handle.failure != null) rethrow(handle.failure);
            var failed = scheduler.failureBarrier().record().orElse(null);
            if (failed != null) rethrow(failed.cause());
            if (!handle.completed()) reservation.retire(ManagedBlockPollTurnFence.State.STALE);
        } finally { pending.remove(operationId); }
    }

    public dev.phoenium.scheduler.GameplayWorkerConfig workerConfig() { return scheduler.workerConfig(); }
    public dev.phoenium.scheduler.GameplayMeasurements measurements() { return scheduler.measurements(); }
    void requireHealthyLocalContinuation() {
        var failure = scheduler.failureBarrier().record().orElse(null);
        if (failure != null) rethrow(failure.cause());
    }

    /** An explicit finite cohort of original local callbacks. The caller establishes
     * independence and phase ordering before submission; this API still resolves the
     * current owner at delivery. Same-owner work is grouped in original order. */
    void executeLocalBatch(java.util.List<LocalWork> cohort) {
        if (!executePreparedLocalBatch(cohort)) throw new IllegalStateException("unconditional local work was declined");
    }
    boolean executePreparedLocalBatch(java.util.List<LocalWork> cohort) {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("local batch holds owner authority");
        if (cohort.size() > scheduler.workerCount()) throw new IllegalArgumentException("local batch exceeds capacity");
        drainBeforeSelection();
        java.util.LinkedHashMap<RegionId, LocalWork> owners = new java.util.LinkedHashMap<>();
        for (LocalWork work : cohort) {
            RegionId owner = directory.resolve(work.key()).orElseThrow().region();
            if (owners.putIfAbsent(owner, work) != null) throw new IllegalArgumentException("local cohort has conflicting owners");
        }
        java.util.List<Handle> handles = new java.util.ArrayList<>();
        owners.values().forEach(work -> handles.add(submit(work.key(), RegionalServiceKind.LOCAL_BLOCK_ENTITY_TICK,
            work.body(), true, work.preparation())));
        try {
            while (handles.stream().anyMatch(handle -> !handle.completed())) {
                var failure = scheduler.failureBarrier().record().orElse(null);
                if (failure != null) rethrow(failure.cause());
                // A topology publication may converge two formerly independent owners
                // before selection. Cancel the whole unexecuted cohort and acknowledge
                // its envelopes through ordinary delivery before choosing serial bodies.
                if (cohort.stream().map(work -> directory.resolve(work.key()).orElseThrow().region()).distinct().count() != cohort.size()) {
                    handles.forEach(handle -> handle.declined = true);
                    handles.forEach(this::drain);
                    return false;
                }
                java.util.Set<Long> operations = handles.stream().filter(handle -> !handle.completed())
                    .map(Handle::operationId).collect(java.util.stream.Collectors.toSet());
                if (scheduler.advanceLocalOperations(serviceId, worldInstance, operations) == 0
                        && !scheduler.advanceNonLocalOne() && !boundaryAdvancer.getAsBoolean())
                    throw new IllegalStateException("local batch has no progress at its current ownership boundary");
            }
            var failure = scheduler.failureBarrier().record().orElse(null);
            if (failure != null) rethrow(failure.cause());
            return handles.stream().noneMatch(handle -> handle.declined);
        } finally {
            handles.stream().filter(Handle::failed).forEach(handle -> pending.remove(handle.operationId()));
        }
    }
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("local gameplay cohort failed", failure);
    }
    @FunctionalInterface interface LocalPreparation { boolean prepare(Execution execution) throws Exception; }
    record LocalWork(RoutingKey key, LocalPreparation preparation, RegionalServiceWork body) {
        LocalWork(RoutingKey key, RegionalServiceWork body) { this(key, ignored -> true, body); }
        public LocalWork { Objects.requireNonNull(key); Objects.requireNonNull(preparation); Objects.requireNonNull(body); }
    }

    /** Finish already accepted owner work before taking a native service's selection cut.
     * Otherwise the first synchronous service drain can run an older movement envelope after
     * that cut and invalidate an untouched tracker. This uses ordinary FIFO owner delivery. */
    public void drainBeforeSelection() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("service selection boundary still has owner authority");
        while (true) {
            var failed = scheduler.failureBarrier().record().orElse(null);
            if (failed != null) throw new IllegalStateException("service selection stopped by owner failure", failed.cause());
            if (!scheduler.acceptsSimulationWork()) throw new IllegalStateException("service selection after scheduler stop");
            long before = scheduler.deliveryAttempts();
            scheduler.advanceOne();
            if (scheduler.deliveryAttempts() == before && !boundaryAdvancer.getAsBoolean()) return;
        }
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
            if (!retained.handle().declined)
                retained.body().run(new Execution(serviceId, worldInstance, operation.operationId(), retained.kind(), retained.key(), runtime));
        } catch (Exception | Error failure) {
            if (retained.completeAfterRelease()) terminalAfterRelease(runtime, operation.operationId(), retained, failure);
            else retained.handle().failed(failure);
            throw failure;
        }
        if (retained.completeAfterRelease()) {
            terminalAfterRelease(runtime, operation.operationId(), retained, null);
            return;
        }
        if (pending.remove(operation.operationId()) != retained)
            throw new IllegalStateException("regional service operation identity changed during execution");
        retained.handle().complete();
    }

    private void terminalAfterRelease(RegionRuntime runtime, long operationId, Pending retained, Throwable failure) {
        runtime.leases().whenReleased(() -> {
            if (!pending.remove(operationId, retained))
                throw new IllegalStateException("regional service operation identity changed before lease release");
            if (failure == null) retained.handle().complete();
            else retained.handle().failed(failure);
        });
    }

    public int pendingCount() { return pending.size(); }
    public boolean hasPending(RegionalServiceKind kind) {
        Objects.requireNonNull(kind, "kind");
        return pending.values().stream().anyMatch(value -> value.kind() == kind);
    }
    public long worldInstance() { return worldInstance; }
    public String serviceId() { return serviceId; }

    /** Stop admission cleanup; retained bodies are released without executing them from the caller. */
    public int abandonPending() {
        if (scheduler.batchActive() || runtimes.values().stream().anyMatch(runtime -> runtime.leases().hasClaimOrSelection()))
            throw new IllegalStateException("cannot abandon a regional body before claim settlement");
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

    @Override public boolean locallyAdmitted(TaskPayload.RegionalService operation) {
        Pending retained = pending.get(operation.operationId());
        return operation.serviceId().equals(serviceId) && operation.worldInstance() == worldInstance
            && retained != null && retained.local() && retained.kind().name().equals(operation.operationKind());
    }
    @Override public long enqueuedNanos(TaskPayload.RegionalService operation) {
        if (!locallyAdmitted(operation)) throw new IllegalStateException("invalid local enqueue receipt");
        return pending.get(operation.operationId()).enqueuedNanos();
    }
    @Override public boolean prepareLocal(RegionRuntime runtime, TaskPayload.RegionalService operation) throws Exception {
        if (!locallyAdmitted(operation)) throw new IllegalStateException("unretained local preflight");
        Pending retained = pending.get(operation.operationId());
        requireCurrentOwner(retained.key());
        return !retained.handle().declined && retained.preparation().prepare(
            new Execution(serviceId, worldInstance, operation.operationId(), retained.kind(), retained.key(), runtime));
    }
    @Override public void declineLocal(TaskPayload.RegionalService operation) {
        if (!locallyAdmitted(operation)) throw new IllegalStateException("unretained local cancellation");
        pending.get(operation.operationId()).handle().declined = true;
    }
    @Override public boolean localDeclined(TaskPayload.RegionalService operation) {
        return locallyAdmitted(operation) && pending.get(operation.operationId()).handle().declined;
    }
    private record Pending(RoutingKey key, RegionalServiceKind kind, RegionalServiceWork body, Handle handle,
                           boolean local, long enqueuedNanos, LocalPreparation preparation,
                           boolean completeAfterRelease) { }
    public final class Handle {
        private final RegionalServiceDispatcher owner = RegionalServiceDispatcher.this;
        private final String serviceId;
        private final long worldInstance;
        private final long operationId;
        private final RegionalServiceKind kind;
        private volatile Outcome state = Outcome.PENDING;
        private volatile boolean declined;
        private volatile Throwable failure;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Handle(String serviceId, long worldInstance, long operationId, RegionalServiceKind kind) {
            this.serviceId = serviceId; this.worldInstance = worldInstance; this.operationId = operationId; this.kind = kind;
        }
        public String serviceId() { return serviceId; }
        public long worldInstance() { return worldInstance; }
        public long operationId() { return operationId; }
        public RegionalServiceKind kind() { return kind; }
        public boolean completed() { return state == Outcome.COMPLETE; }
        public boolean failed() { return state == Outcome.FAILED; }
        public CompletionStage<Void> completion() { return completion; }
        private void complete() {
            if (state != Outcome.PENDING) throw new IllegalStateException("service handle already terminal");
            state = Outcome.COMPLETE;
            completion.complete(null);
        }
        private void failed(Throwable failure) {
            if (state != Outcome.PENDING) throw new IllegalStateException("service handle already terminal");
            this.failure = Objects.requireNonNull(failure);
            state = Outcome.FAILED;
            completion.completeExceptionally(failure);
        }
        private void abandoned() {
            if (state == Outcome.PENDING) {
                state = Outcome.ABANDONED;
                completion.completeExceptionally(new IllegalStateException("regional service was abandoned: " + this));
            }
        }
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
