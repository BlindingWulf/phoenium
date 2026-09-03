package dev.phoenium.live;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.scheduler.SchedulerLifecycle;
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
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Stage 2's serial live binding. A Minecraft level is represented by one real
 * Phoenium region; this is deliberately not geographical regionalization.
 */
public final class PhoeniumLiveRuntime implements RegionScheduler.LiveTickExecutor {
    private final Logger logger;
    private final Map<WorldId, LiveRegion> regions = new LinkedHashMap<>();
    private final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>();
    private final RegionDirectory directory = new RegionDirectory();
    private final FailureBarrier barrier = new FailureBarrier();
    private RegionScheduler scheduler;
    private OwnerGateway gateway;
    private PendingTick pendingTick;
    private State state = State.NEW;
    private long lifecycleStarts;
    private long lifecycleStops;
    private long intercepts;
    private long dispatches;
    private long executions;
    private long duplicates;
    private long unexpectedBypasses;
    private long fallbacks;
    private long ownershipViolations;
    private long unleasedExecutions;
    private long hardBarriers;
    private long sequence;
    private final long injectedFailureAt = Long.getLong("phoenium.test.failLiveTickAt", -1L);

    public PhoeniumLiveRuntime(Logger logger) { this.logger = Objects.requireNonNull(logger, "logger"); }

    public void start(MinecraftServer server) {
        if (state != State.NEW) throw new IllegalStateException("Phoenium lifecycle start repeated: " + state);
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            WorldId world = worldId(level);
            RegionId region = new RegionId(world, "stage2-live-world");
            RoutingKey key = new RoutingKey(world, 0, 0);
            RegionRuntime runtime = new RegionRuntime(region, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            LiveRegion previous = regions.put(world, new LiveRegion(level, region, key, runtime));
            if (previous != null) throw new IllegalStateException("duplicate controlled world " + world.value());
            runtimes.put(region, runtime);
            routes.put(key, new RegionRoute(region, new TopologyGeneration(1), runtime.ownerEpoch()));
        }
        if (regions.isEmpty()) throw new IllegalStateException("no server levels available for Stage 2 live binding");
        directory.publish(new TopologyGeneration(1), routes);
        scheduler = new RegionScheduler(runtimes, directory, barrier, this);
        gateway = new OwnerGateway(directory, runtimes, scheduler);
        state = State.RUNNING;
        lifecycleStarts++;
        logStatus("started");
    }

    /** Called only from the redirect around MinecraftServer.tickChildren' single ServerLevel.tick invoke. */
    public void dispatchLevelTick(ServerLevel level, BooleanSupplier shouldKeepTicking) {
        intercepts++;
        if (!isRunning()) {
            // The redirect deliberately consumes the call after a hard barrier. It never invokes vanilla as a fallback.
            if (scheduler != null && scheduler.lifecycle() == SchedulerLifecycle.FAILED) hardBarriers = 1;
            return;
        }
        WorldId world = worldId(level);
        LiveRegion live = regions.get(world);
        if (live == null || live.level() != level) {
            unexpectedBypasses++;
            failClosed(new IllegalStateException("unregistered live level " + world.value()), null);
            return;
        }
        RegionRoute route = directory.resolve(live.key()).orElseThrow(() -> new IllegalStateException("missing route " + world.value()));
        RegionTaskEnvelope envelope = new RegionTaskEnvelope("live-level-tick", new TaskPayload.LiveLevelTick(world.value()), live.key(), route.generation(), route.ownerEpoch(), "minecraft-server", new TaskCorrelationId(++sequence), new TaskSequence(sequence), AdmissionPolicy.REJECT);
        if (gateway.submit(envelope) != OwnerGateway.Delivery.QUEUED) {
            unexpectedBypasses++;
            failClosed(new IllegalStateException("live tick admission rejected for " + world.value()), envelope);
            return;
        }
        dispatches++;
        pendingTick = new PendingTick(level, shouldKeepTicking, envelope);
        try {
            scheduler.runOne(live.runtime());
        } finally {
            pendingTick = null;
        }
        if (scheduler.lifecycle() == SchedulerLifecycle.FAILED) hardBarriers = 1;
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.LiveLevelTick tick) {
        PendingTick pending = pendingTick;
        if (pending == null || !tick.worldKey().equals(worldId(pending.level()).value())) {
            unleasedExecutions++;
            throw new IllegalStateException("live tick execution was not bound to its admitted envelope");
        }
        if (injectedFailureAt > 0 && dispatches == injectedFailureAt) {
            throw new IllegalStateException("Stage 2 requested live tick failure at dispatch " + dispatches);
        }
        pending.level().tick(pending.shouldKeepTicking());
        executions++;
        runtime.tick();
    }

    public void stop() {
        if (state == State.STOPPED) return;
        if (scheduler != null) { scheduler.quiesce(); scheduler.stop(); }
        state = State.STOPPED;
        lifecycleStops++;
        logStatus("stopped");
    }

    public LiveDiagnostics diagnostics() {
        int activeLeases = runtimes.values().stream().mapToInt(runtime -> runtime.leases().hasActiveLease() ? 1 : 0).sum();
        return new LiveDiagnostics(state, scheduler == null ? null : scheduler.lifecycle(), lifecycleStarts, lifecycleStops, 1, false,
            regions.size(), activeLeases, intercepts, dispatches, executions, duplicates, unexpectedBypasses, fallbacks,
            ownershipViolations, unleasedExecutions, hardBarriers, barrier.tripped());
    }

    private void failClosed(Throwable failure, RegionTaskEnvelope envelope) {
        if (!barrier.tripped()) {
            barrier.trip(failure, directory.snapshot().generation(), new OwnerEpoch(0), envelope);
            hardBarriers = 1;
        }
    }
    private boolean isRunning() { return state == State.RUNNING && scheduler != null && scheduler.lifecycle().acceptsSimulationWork(); }
    private static WorldId worldId(ServerLevel level) { return new WorldId(level.dimension().identifier().toString()); }
    private void logStatus(String event) { logger.info("PHOENIUM_STAGE2 {} {}", event, diagnostics().toLogLine()); }

    private record LiveRegion(ServerLevel level, RegionId id, RoutingKey key, RegionRuntime runtime) { }
    private record PendingTick(ServerLevel level, BooleanSupplier shouldKeepTicking, RegionTaskEnvelope envelope) { }
    public enum State { NEW, RUNNING, STOPPED }
}
