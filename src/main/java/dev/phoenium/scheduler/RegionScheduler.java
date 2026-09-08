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

/** One explicit worker, deterministic region order, and no alternative execution route. */
public final class RegionScheduler {
    private final Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes;
    private final RegionDirectory directory;
    private final FailureBarrier barrier;
    private final LiveTickExecutor liveTickExecutor;
    private final ObjectOperationExecutor objectOperationExecutor;
    private SchedulerLifecycle lifecycle = SchedulerLifecycle.RUNNING;
    private long advances;
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
        this.runtimes = runtimes; this.directory = directory; this.barrier = barrier; this.liveTickExecutor = liveTickExecutor;
        this.objectOperationExecutor = objectOperationExecutor;
    }
    public int workerCount() { return 1; }
    public SchedulerLifecycle lifecycle() { return lifecycle; }
    public FailureBarrier failureBarrier() { return barrier; }
    public long advances() { return advances; }
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
    public void quiesce() { if (lifecycle == SchedulerLifecycle.RUNNING) lifecycle = SchedulerLifecycle.QUIESCING; }
    /** Shutdown never drains envelopes on the caller thread; pending work stays unexecuted. */
    public void stop() { if (lifecycle != SchedulerLifecycle.FAILED) lifecycle = SchedulerLifecycle.STOPPING; }
    public void advanceOne() {
        if (!lifecycle.acceptsSimulationWork()) return;
        runtimes.values().stream().filter(runtime -> runtime.lifecycle() == RegionLifecycle.READY && !runtime.transitioning() && runtime.inboxSize() > 0)
            .sorted(Comparator.comparing(RegionRuntime::id)).findFirst().ifPresent(this::runOne);
    }
    public boolean runOne(RegionRuntime runtime) {
        if (!lifecycle.acceptsSimulationWork() || runtime.transitioning()) return false;
        RegionTaskEnvelope envelope = runtime.poll().orElse(null);
        if (envelope == null) return false;
        if (envelope.payload() instanceof TaskPayload.OwnedObjectOperation) {
            if (objectDeliveryValidator == null || !objectDeliveryValidator.revalidateForExecution(runtime, envelope)) return true;
        } else {
            if (locationValidator == null || !locationValidator.revalidateLocationForExecution(runtime, envelope)) return true;
        }
        try (RegionLease lease = runtime.leases().acquire(); OwnershipContext.Scope ignored = OwnershipContext.enter(runtime.id(), lease.epoch(), OperationCategory.OWNER_TASK)) {
            runtime.transitionTo(RegionLifecycle.TICKING);
            lease.requireCurrent();
            apply(runtime, envelope.payload());
            advances++;
            runtime.transitionTo(RegionLifecycle.READY);
            return true;
        } catch (Throwable failure) {
            lifecycle = SchedulerLifecycle.FAILED;
            barrier.trip(failure, directory.snapshot().generation(), runtime.ownerEpoch(), envelope);
            return true;
        }
    }
    /** Tick work has exactly the same lease/context mechanism as owner-envelope work. */
    public void runTick(RegionRuntime runtime) {
        if (!lifecycle.acceptsSimulationWork() || runtime.transitioning()) return;
        try (RegionLease lease = runtime.leases().acquire(); OwnershipContext.Scope ignored = OwnershipContext.enter(runtime.id(), lease.epoch(), OperationCategory.TICK)) {
            runtime.transitionTo(RegionLifecycle.TICKING);
            lease.requireCurrent();
            runtime.tick();
            advances++;
            runtime.transitionTo(RegionLifecycle.READY);
        } catch (Throwable failure) {
            lifecycle = SchedulerLifecycle.FAILED;
            barrier.trip(failure, directory.snapshot().generation(), runtime.ownerEpoch(), null);
        }
    }
    public void snapshot(RegionRuntime runtime, Runnable sink) {
        if (!lifecycle.acceptsSimulationWork()) return;
        try (RegionLease lease = runtime.leases().acquire(); OwnershipContext.Scope ignored = OwnershipContext.enter(runtime.id(), lease.epoch(), OperationCategory.SAVE_SNAPSHOT)) {
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
