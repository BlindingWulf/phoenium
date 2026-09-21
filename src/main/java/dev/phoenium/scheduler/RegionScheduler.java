package dev.phoenium.scheduler;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLease;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.ownership.ObjectOperationExecutor;
import dev.phoenium.ownership.ObjectOwnerGateway;
import dev.phoenium.service.RegionalServiceExecutor;
import java.util.Comparator;
import java.util.Map;

/** Ordered native phases and explicitly admitted bounded regional cohorts share the
 * same typed delivery, current lease and failure barrier. */
public final class RegionScheduler {
    private final Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes;
    private final RegionDirectory directory;
    private final FailureBarrier barrier;
    private final LiveTickExecutor liveTickExecutor;
    private final ObjectOperationExecutor objectOperationExecutor;
    private volatile SchedulerLifecycle lifecycle = SchedulerLifecycle.RUNNING;
    private final java.util.concurrent.atomic.AtomicLong advances = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong deliveryAttempts = new java.util.concurrent.atomic.AtomicLong();
    private final GameplayWorkerConfig config;
    private final BoundedGameplayWorkers workers;
    private final GameplayMeasurements measurements = new GameplayMeasurements();
    private final java.util.concurrent.locks.ReentrantLock admission = new java.util.concurrent.locks.ReentrantLock();
    private ObjectOwnerGateway objectDeliveryValidator;
    private OwnerGateway locationValidator;
    private RegionalServiceExecutor regionalServiceExecutor;

    public RegionScheduler(Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, RegionDirectory directory, FailureBarrier barrier) {
        this(runtimes, directory, barrier, null, null);
    }
    public RegionScheduler(Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, RegionDirectory directory, FailureBarrier barrier, LiveTickExecutor liveTickExecutor) {
        this(runtimes, directory, barrier, liveTickExecutor, null);
    }
    public RegionScheduler(Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, RegionDirectory directory, FailureBarrier barrier,
                           LiveTickExecutor liveTickExecutor, ObjectOperationExecutor objectOperationExecutor) {
        this(runtimes, directory, barrier, liveTickExecutor, objectOperationExecutor, GameplayWorkerConfig.serial());
    }
    public RegionScheduler(Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, RegionDirectory directory, FailureBarrier barrier,
                           LiveTickExecutor liveTickExecutor, ObjectOperationExecutor objectOperationExecutor, GameplayWorkerConfig config) {
        this.runtimes = runtimes; this.directory = directory; this.barrier = barrier; this.liveTickExecutor = liveTickExecutor;
        this.objectOperationExecutor = objectOperationExecutor;
        this.config = java.util.Objects.requireNonNull(config);
        this.workers = new BoundedGameplayWorkers(config.workers());
    }
    public int workerCount() { return config.workers(); }
    public GameplayWorkerConfig workerConfig() { return config; }
    public int queuedWorkerTasks() { return workers.queued(); }
    public int liveWorkerThreads() { return workers.threads(); }
    public boolean batchActive() { return workers.batchActive(); }
    public GameplayMeasurements measurements() { return measurements; }
    public SchedulerLifecycle lifecycle() { return lifecycle; }
    public boolean acceptsSimulationWork() { return lifecycle.acceptsSimulationWork() && !barrier.tripped(); }
    public FailureBarrier failureBarrier() { return barrier; }
    public long advances() { return advances.get(); }
    /** Includes stale drops and reroutes as well as successful bodies; used only for event-loop progress. */
    public long deliveryAttempts() { return deliveryAttempts.get(); }
    /** Installed by the typed object gateway; validation remains at envelope delivery, never ingress only. */
    public void installObjectDeliveryValidator(ObjectOwnerGateway validator) { this.objectDeliveryValidator = validator; }
    /** Installed by the generic owner gateway; every location task revalidates at execution, never ingress only. */
    public void installLocationValidator(OwnerGateway validator) { this.locationValidator = validator; }
    /** Installed by the sole retained regional-service dispatcher for this scheduler. */
    public void installRegionalServiceExecutor(RegionalServiceExecutor executor) {
        if (regionalServiceExecutor != null && regionalServiceExecutor != executor)
            throw new IllegalStateException("regional service executor already installed");
        regionalServiceExecutor = executor;
    }
    public void quiesce() {
        admission.lock(); try { if (lifecycle == SchedulerLifecycle.RUNNING) lifecycle = SchedulerLifecycle.QUIESCING; }
        finally { admission.unlock(); }
    }
    /** Metadata-only ingress cut. Stop cannot acknowledge queues while an accepted
     * producer still owns its final insertion. No original callback runs under this gate. */
    public <T> T admit(java.util.function.Supplier<T> metadataInsertion, T stoppedResult) {
        admission.lock(); try { return acceptsSimulationWork() ? metadataInsertion.get() : stoppedResult; }
        finally { admission.unlock(); }
    }
    /** Shutdown never drains envelopes on the caller thread; pending work stays unexecuted. */
    public void stop() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("scheduler stop holds owner authority");
        admission.lock(); try { if (lifecycle != SchedulerLifecycle.FAILED) lifecycle = SchedulerLifecycle.STOPPING; }
        finally { admission.unlock(); }
        workers.stop();
    }
    /** Explicit terminal acknowledgement after retained service/packet claims settle. Only
     * immutable envelopes leave here; no payload or original callback is ever executed. */
    public int acknowledgeStoppedEnvelopes() {
        if (lifecycle.acceptsSimulationWork() || OwnershipContext.current().isPresent()
                || workers.batchActive() || runtimes.values().stream().anyMatch(runtime -> runtime.leases().hasClaimOrSelection()))
            throw new IllegalStateException("queued work acknowledged before scheduler shutdown");
        int acknowledged = 0;
        for (RegionRuntime runtime : runtimes.values()) {
            while (runtime.poll().isPresent()) acknowledged++;
            while (runtime.pollTransitionMailbox().isPresent()) acknowledged++;
        }
        return acknowledged;
    }
    public void advanceOne() {
        if (!acceptsSimulationWork()) return;
        for (RegionRuntime runtime : runtimes.values().stream()
                .filter(value -> value.lifecycle() == RegionLifecycle.READY && !value.transitioning() && value.inboxSize() > 0)
                .sorted(Comparator.comparing(RegionRuntime::id)).toList()) {
            if (runOne(runtime)) return;
        }
    }
    /** One already-reserved native suffix, before another ordinary arbitration. This
     * uses the same selection, lease, delivery validation and failure barrier as runOne. */
    public boolean resumeNaturalSpawn(dev.phoenium.service.ManagedBlockPollTurnFence.Reservation reservation,
                                     RegionTaskEnvelope envelope) {
        reservation.requireResumeClaim();
        if (!(envelope.payload() instanceof TaskPayload.RegionalService service)
                || !(service.operationKind().equals(dev.phoenium.service.RegionalServiceKind.CHUNK_GAMEPLAY.name())
                    || service.operationKind().equals(dev.phoenium.service.RegionalServiceKind.BLOCK_ENTITY_TICK.name())))
            throw new IllegalArgumentException("poll return reservation is only for natural spawn or Vault tick");
        if (!acceptsSimulationWork()) return false;
        var route = directory.resolve(envelope.routingKey()).orElse(null);
        if (route == null) return false;
        var runtime = runtimes.get(route.region());
        if (runtime == null || runtime.transitioning() || runtime.lifecycle() != RegionLifecycle.READY) return false;
        var selection = runtime.leases().trySelect().orElse(null);
        if (selection == null) return false;
        new Selected(runtime, envelope, selection, true).run();
        return true;
    }
    public boolean runOne(RegionRuntime runtime) {
        var head = runtime.peek().orElse(null);
        if (head != null && head.payload() instanceof TaskPayload.RegionalService service
                && service.operationKind().equals(dev.phoenium.service.RegionalServiceKind.LOCAL_BLOCK_ENTITY_TICK.name())
                && regionalServiceExecutor != null && !regionalServiceExecutor.localDeclined(service)) return false;
        Selected selected = select(runtime, false);
        if (selected == null) return false;
        selected.run(); return true;
    }
    public boolean advanceNonLocalOne() {
        if (!acceptsSimulationWork()) return false;
        for (RegionRuntime runtime : runtimes.values().stream().sorted(Comparator.comparing(RegionRuntime::id)).toList()) {
            var head = runtime.peek().orElse(null);
            if (head != null && !(head.payload() instanceof TaskPayload.RegionalService service
                    && service.operationKind().equals(dev.phoenium.service.RegionalServiceKind.LOCAL_BLOCK_ENTITY_TICK.name()))
                    && runOne(runtime)) return true;
        }
        return false;
    }

    /** Only the audited local block-entity operation is eligible for worker dispatch.
     * Native server tasks, FULL, packets, saves and shared gameplay keep their existing
     * ordered coordinator phases. Queue insertion alone never grants parallel eligibility. */
    public int advanceLocalBatch(java.util.List<RegionRuntime> cohort) {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("parallel selection retains an owner");
        if (cohort.size() > workerCount()) throw new IllegalArgumentException("parallel cohort exceeds configured bound");
        if (new java.util.HashSet<>(cohort).size() != cohort.size()) throw new IllegalArgumentException("duplicate region in parallel cohort");
        java.util.List<Selected> selected = new java.util.ArrayList<>();
        boolean admitted = false;
        try {
            admission.lock(); try {
                if (!acceptsSimulationWork()) return 0;
                workers.begin(); admitted = true;
            } finally { admission.unlock(); }
            for (RegionRuntime runtime : cohort) {
                Selected next = select(runtime, true);
                if (next != null) selected.add(next);
            }
            if (selected.size() != cohort.size()) return 0;
            boolean eligible = true;
            for (Selected value : selected) {
                if (!value.prepareLocal()) return selected.size(); // ordinary stale reroute, no body selected
                eligible &= value.localEligible;
            }
            if (!eligible) for (Selected value : selected)
                regionalServiceExecutor.declineLocal((TaskPayload.RegionalService) value.envelope.payload());
            workers.execute(selected.stream().<Runnable>map(value -> value).toList());
        } catch (Throwable failure) {
            fail(failure, selected.isEmpty() ? null : selected.getFirst().runtime,
                selected.isEmpty() ? null : selected.getFirst().envelope);
        } finally {
            // A rejected submission retains its envelope; no caller-thread replay.
            try {
                for (Selected value : selected) {
                    try { value.releaseUnstarted(); }
                    catch (Throwable failure) { fail(failure, value.runtime, value.envelope); }
                }
            } finally { if (admitted) workers.finish(); }
        }
        return selected.size();
    }

    /** Follow physical queues as well as current routes. A delivery racing a topology
     * publication can reside in its former inbox and must reroute without being lost. */
    public int advanceLocalOperations(String serviceId, long worldInstance, java.util.Set<Long> operations) {
        var cohort = runtimes.values().stream()
            .filter(runtime -> runtime.lifecycle() == RegionLifecycle.READY && !runtime.transitioning())
            .filter(runtime -> runtime.peek().map(envelope -> envelope.payload() instanceof TaskPayload.RegionalService service
                && service.serviceId().equals(serviceId) && service.worldInstance() == worldInstance
                && operations.contains(service.operationId())).orElse(false))
            .sorted(Comparator.comparing(RegionRuntime::id)).limit(workerCount()).toList();
        if (cohort.size() != operations.size()) return 0;
        return advanceLocalBatch(cohort);
    }

    private Selected select(RegionRuntime runtime, boolean localOnly) {
        if (!acceptsSimulationWork() || runtime.transitioning() || runtime.lifecycle() != RegionLifecycle.READY) return null;
        var selection = runtime.leases().trySelect().orElse(null);
        if (selection == null) return null;
        boolean retained = false;
        try {
            RegionTaskEnvelope head = runtime.peek().orElse(null);
            if (head == null || localOnly && (!(head.payload() instanceof TaskPayload.RegionalService service)
                    || !service.operationKind().equals(dev.phoenium.service.RegionalServiceKind.LOCAL_BLOCK_ENTITY_TICK.name())
                    || regionalServiceExecutor == null || !regionalServiceExecutor.locallyAdmitted(service))) return null;
            if (!acceptsSimulationWork()) return null;
            RegionTaskEnvelope envelope = runtime.poll().orElseThrow();
            if (head != envelope) throw new IllegalStateException("owner inbox was polled outside its selected claim");
            Selected result = new Selected(runtime, envelope, selection);
            retained = true; return result;
        } finally { if (!retained) selection.close(); }
    }

    private final class Selected implements Runnable {
        private final RegionRuntime runtime;
        private final RegionTaskEnvelope envelope;
        private final dev.phoenium.ownership.RegionLeaseManager.Selection selection;
        private final boolean nativeReturn;
        private final long selectedAt = System.nanoTime();
        private volatile boolean started;
        private RegionLease preparedLease;
        private long preparedClaimStart;
        private boolean localEligible;
        private Selected(RegionRuntime runtime, RegionTaskEnvelope envelope, dev.phoenium.ownership.RegionLeaseManager.Selection selection) {
            this(runtime, envelope, selection, false);
        }
        private Selected(RegionRuntime runtime, RegionTaskEnvelope envelope,
                         dev.phoenium.ownership.RegionLeaseManager.Selection selection, boolean nativeReturn) {
            this.runtime = runtime; this.envelope = envelope; this.selection = selection; this.nativeReturn = nativeReturn;
        }
        private boolean prepareLocal() throws Exception {
            deliveryAttempts.incrementAndGet();
            if (locationValidator == null || !locationValidator.revalidateLocationForExecution(runtime, envelope)) {
                started = true; selection.close(); return false;
            }
            preparedClaimStart = System.nanoTime(); preparedLease = selection.promote();
            try (var owner = OwnershipContext.enter(runtime.id(), preparedLease.epoch(), OperationCategory.OWNER_TASK)) {
                preparedLease.requireCurrent();
                localEligible = regionalServiceExecutor.prepareLocal(runtime, (TaskPayload.RegionalService) envelope.payload());
            }
            return true;
        }
        @Override public void run() {
            started = true;
            GameplayMeasurements.Claim measured = null;
            try (selection) {
                if (!acceptsSimulationWork()) { if (!nativeReturn) runtime.requeueFirst(envelope); return; }
                if (preparedLease == null) deliveryAttempts.incrementAndGet();
                // Current claim fences topology publication through validation AND body.
                boolean valid = preparedLease != null || (envelope.payload() instanceof TaskPayload.OwnedObjectOperation
                    ? objectDeliveryValidator != null && objectDeliveryValidator.revalidateForExecution(runtime, envelope)
                    : locationValidator != null && locationValidator.revalidateLocationForExecution(runtime, envelope));
                if (!valid) return;
                if (!acceptsSimulationWork()) { if (!nativeReturn) runtime.requeueFirst(envelope); return; }
                long claimStart = preparedLease == null ? System.nanoTime() : preparedClaimStart;
                RegionLease executing = preparedLease == null ? selection.promote() : preparedLease;
                preparedLease = null;
                try (RegionLease lease = executing;
                     OwnershipContext.Scope ignored = OwnershipContext.enter(runtime.id(), lease.epoch(), OperationCategory.OWNER_TASK)) {
                    runtime.transitionTo(RegionLifecycle.TICKING);
                    try {
                        lease.requireCurrent();
                        if (envelope.payload() instanceof TaskPayload.RegionalService service
                                && service.operationKind().equals(dev.phoenium.service.RegionalServiceKind.LOCAL_BLOCK_ENTITY_TICK.name()))
                            measured = measurements.enter(regionalServiceExecutor.enqueuedNanos(service), selectedAt, claimStart,
                                directory.snapshot().generation().value(), service.operationId());
                        apply(runtime, envelope.payload());
                        advances.incrementAndGet();
                    } catch (Throwable failure) { fail(failure, runtime, envelope); }
                    finally { measurements.leaveBody(); runtime.transitionTo(RegionLifecycle.READY); }
                }
            } catch (Throwable failure) { fail(failure, runtime, envelope); }
            finally {
                if (preparedLease != null) { preparedLease.close(); preparedLease = null; }
                measurements.released(measured);
            }
        }
        private void releaseUnstarted() {
            if (!started) {
                runtime.requeueFirst(envelope);
                if (preparedLease != null) { preparedLease.close(); preparedLease = null; }
                selection.close(); started = true;
            }
        }
    }

    private void fail(Throwable failure, RegionRuntime runtime, RegionTaskEnvelope envelope) {
        barrier.trip(failure, directory.snapshot().generation(), runtime == null ? new OwnerEpoch(0) : runtime.ownerEpoch(), envelope);
        lifecycle = SchedulerLifecycle.FAILED;
    }
    /** Tick work has exactly the same lease/context mechanism as owner-envelope work. */
    public void runTick(RegionRuntime runtime) {
        if (!acceptsSimulationWork() || runtime.transitioning()) return;
        RegionLease acquired = runtime.leases().tryAcquire().orElse(null);
        if (acquired == null) return;
        try (RegionLease lease = acquired; OwnershipContext.Scope ignored = OwnershipContext.enter(runtime.id(), lease.epoch(), OperationCategory.TICK)) {
            runtime.transitionTo(RegionLifecycle.TICKING);
            lease.requireCurrent();
            runtime.tick();
            advances.incrementAndGet();
            runtime.transitionTo(RegionLifecycle.READY);
        } catch (Throwable failure) {
            lifecycle = SchedulerLifecycle.FAILED;
            barrier.trip(failure, directory.snapshot().generation(), runtime.ownerEpoch(), null);
        }
    }
    public void snapshot(RegionRuntime runtime, Runnable sink) {
        if (!acceptsSimulationWork()) return;
        RegionLease acquired = runtime.leases().tryAcquire().orElse(null);
        if (acquired == null) return;
        try (RegionLease lease = acquired; OwnershipContext.Scope ignored = OwnershipContext.enter(runtime.id(), lease.epoch(), OperationCategory.SAVE_SNAPSHOT)) {
            sink.run();
        } catch (Throwable failure) {
            lifecycle = SchedulerLifecycle.FAILED;
            barrier.trip(failure, directory.snapshot().generation(), runtime.ownerEpoch(), null);
        }
    }
    private void apply(RegionRuntime runtime, TaskPayload payload) throws Exception {
        switch (payload) {
            case TaskPayload.Append append -> runtime.append(append.value());
            case TaskPayload.Add add -> runtime.add(add.value());
            case TaskPayload.Fail fail -> throw new IllegalStateException(fail.message());
            case TaskPayload.LiveLevelTick liveLevelTick -> {
                if (liveTickExecutor == null) throw new IllegalStateException("live tick payload without live executor");
                liveTickExecutor.execute(runtime, liveLevelTick);
            }
            case TaskPayload.OwnedObjectOperation operation -> {
                if (objectOperationExecutor == null) throw new IllegalStateException("owned object operation without ownership executor");
                objectOperationExecutor.execute(runtime, operation);
            }
            case TaskPayload.ChunkCompletion completion -> {
                if (objectOperationExecutor == null) throw new IllegalStateException("chunk completion without ownership executor");
                objectOperationExecutor.execute(runtime, completion);
            }
            case TaskPayload.RegionalService service -> {
                if (regionalServiceExecutor == null) throw new IllegalStateException("regional service payload without service executor");
                regionalServiceExecutor.execute(runtime, service);
            }
        }
    }

    /** A dedicated typed live operation, not a generic cross-owner Runnable escape hatch. */
    @FunctionalInterface
    public interface LiveTickExecutor {
        void execute(RegionRuntime runtime, TaskPayload.LiveLevelTick tick) throws Exception;
    }
}
