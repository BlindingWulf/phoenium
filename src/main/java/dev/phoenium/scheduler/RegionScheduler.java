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
import java.util.Comparator;
import java.util.Map;

/** One explicit worker, deterministic region order, and no alternative execution route. */
public final class RegionScheduler {
    private final Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes;
    private final RegionDirectory directory;
    private final FailureBarrier barrier;
    private SchedulerLifecycle lifecycle = SchedulerLifecycle.RUNNING;
    private long advances;

    public RegionScheduler(Map<dev.phoenium.region.RegionId, RegionRuntime> runtimes, RegionDirectory directory, FailureBarrier barrier) {
        this.runtimes = runtimes; this.directory = directory; this.barrier = barrier;
    }
    public int workerCount() { return 1; }
    public SchedulerLifecycle lifecycle() { return lifecycle; }
    public FailureBarrier failureBarrier() { return barrier; }
    public long advances() { return advances; }
    public void quiesce() { if (lifecycle == SchedulerLifecycle.RUNNING) lifecycle = SchedulerLifecycle.QUIESCING; }
    /** Shutdown never drains envelopes on the caller thread; pending work stays unexecuted. */
    public void stop() { if (lifecycle != SchedulerLifecycle.FAILED) lifecycle = SchedulerLifecycle.STOPPING; }
    public void advanceOne() {
        if (!lifecycle.acceptsSimulationWork()) return;
        runtimes.values().stream().filter(runtime -> runtime.lifecycle() == RegionLifecycle.READY && !runtime.transitioning() && runtime.inboxSize() > 0)
            .sorted(Comparator.comparing(RegionRuntime::id)).findFirst().ifPresent(this::runOne);
    }
    public void runOne(RegionRuntime runtime) {
        if (!lifecycle.acceptsSimulationWork() || runtime.transitioning()) return;
        RegionTaskEnvelope envelope = runtime.poll().orElse(null);
        if (envelope == null) return;
        try (RegionLease lease = runtime.leases().acquire(); OwnershipContext.Scope ignored = OwnershipContext.enter(runtime.id(), lease.epoch(), OperationCategory.OWNER_TASK)) {
            runtime.transitionTo(RegionLifecycle.TICKING);
            lease.requireCurrent();
            apply(runtime, envelope.payload());
            advances++;
            runtime.transitionTo(RegionLifecycle.READY);
        } catch (Throwable failure) {
            lifecycle = SchedulerLifecycle.FAILED;
            barrier.trip(failure, directory.snapshot().generation(), runtime.ownerEpoch(), envelope);
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
    private static void apply(RegionRuntime runtime, TaskPayload payload) {
        switch (payload) {
            case TaskPayload.Append append -> runtime.append(append.value());
            case TaskPayload.Add add -> runtime.add(add.value());
            case TaskPayload.Fail fail -> throw new IllegalStateException(fail.message());
        }
    }
}
