package dev.phoenium.live;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.chunk.ChunkCompletionGateway;
import dev.phoenium.chunk.ChunkCustody;
import dev.phoenium.chunk.ChunkWorkRequest;
import dev.phoenium.chunk.ChunkPrerequisiteServices;
import dev.phoenium.chunk.ChunkBindingRuntime;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOperationExecutor;
import dev.phoenium.ownership.ObjectOwnerGateway;
import dev.phoenium.ownership.OwnedObjectEnvelope;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.mixin.ServerGamePacketListenerInvoker;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.scheduler.SchedulerLifecycle;
import dev.phoenium.service.RegionalServiceDispatcher;
import dev.phoenium.service.RegionalWorldServices;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.LiveMovementAdmission;
import dev.phoenium.topology.LivePacketReplayGate;
import dev.phoenium.interaction.InteractionTargetIndex;
import dev.phoenium.interaction.InteractionTargetRef;
import dev.phoenium.interaction.LiveInteractionAdmission;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.core.BlockPos;
import java.util.function.BiConsumer;
import org.slf4j.Logger;

/**
 * Stage 2's serial live binding. A Minecraft level is represented by one real
 * Phoenium region; this is deliberately not geographical regionalization.
 */
public final class PhoeniumLiveRuntime implements RegionScheduler.LiveTickExecutor, ObjectOperationExecutor, ChunkBindingRuntime.Authority {
    private final Logger logger;
    private final Map<WorldId, LiveRegion> regions = new LinkedHashMap<>();
    /** Per-world service lifetime only; no Minecraft chunk callback is bound to it in this pass. */
    private final Map<WorldId, ChunkPrerequisiteServices> chunkPrerequisiteServices = new LinkedHashMap<>();
    private final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>();
    private final RegionDirectory directory = new RegionDirectory();
    private final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
    private final Map<TaskCorrelationId, PendingPacket> packets = new ConcurrentHashMap<>();
    private final InteractionTargetIndex interactionTargets = new InteractionTargetIndex();
    private final EntityIndexLifecycleAdapter entityIndexLifecycle = new EntityIndexLifecycleAdapter(objects, directory, interactionTargets);
    private final Map<TaskCorrelationId, ChunkWorkRequest> chunkRequests = new ConcurrentHashMap<>();
    private final java.util.Set<WorldId> observedChunkFutureWorlds = ConcurrentHashMap.newKeySet();
    private final LivePacketReplayGate movementReplay = new LivePacketReplayGate();
    private final ThreadLocal<ReplayIdentity> replayIdentity = new ThreadLocal<>();
    private final java.util.Set<TaskCorrelationId> consumedInteractionReplays = ConcurrentHashMap.newKeySet();
    private final FailureBarrier barrier = new FailureBarrier();
    private final ChunkBindingRuntime chunkBinding;
    private RegionScheduler scheduler;
    private OwnerGateway gateway;
    private ObjectOwnerGateway objectGateway;
    private ChunkCompletionGateway chunkGateway;
    private RegionalServiceDispatcher regionalDispatcher;
    private RegionalWorldServices regionalServices;
    private dev.phoenium.save.RegionalSaveCoordinator regionalSaves;
    private final ChunkCustody chunkCustody = new ChunkCustody();
    private PendingTick pendingTick;
    private boolean explicitFixture;
    private State state = State.NEW;
    private volatile boolean gameplayAdmissionClosed;
    private boolean settlingRuntime;
    private MinecraftServer serverInstance;
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
    private long packetAdmissions;
    private long packetExecutions;
    private long packetStaleReroutes;
    private long packetRejections;
    private long packetRecursions;
    private long movementIngress, movementBootstrapDeferred, movementFootprintAccepted, movementFootprintRejected, movementOwnerExecution;
    private long movementReplayCount, movementHandlerCompletion, movementRoutingReconciliation, movementSameOwnerEpochPreserved, movementStaleTicketRejected;
    private long interactionIngress, interactionIndexHit, interactionIndexMiss, interactionIncarnationRejected, interactionOwnerAdmission;
    private long interactionCrossOwnerRejected, interactionOwnerExecution, interactionReplay, interactionVanillaLookup, interactionHandlerCompletion, interactionStaleTicketRejected;
    private long chunkCompletionAdmissions;
    private long chunkCompletionApplications;
    private long chunkCompletionReroutes;
    private long chunkCompletionRejections;
    private long chunkCompletionDuplicates;
    private long producerThreadChunkMutations;
    private long sequence;
    private final long injectedFailureAt = Long.getLong("phoenium.test.failLiveTickAt", -1L);

    public PhoeniumLiveRuntime(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.chunkBinding = new ChunkBindingRuntime(this, logger);
    }

    public void start(MinecraftServer server) {
        if (state != State.NEW) throw new IllegalStateException("Phoenium lifecycle start repeated: " + state);
        serverInstance = Objects.requireNonNull(server);
        // Force the target class through Mixin transformation during a no-client boot.  The two
        // required injectors each declare require=1/expect=1, so selector drift is a startup
        // failure rather than a first-client surprise or an early-hook fallback.
        try {
            ClassLoader loader = server.getClass().getClassLoader();
            Class.forName(ServerGamePacketListenerImpl.class.getName(), true, loader);
            Class.forName("net.minecraft.server.players.PlayerList", true, loader);
            Class.forName(Entity.class.getName(), true, loader);
            Class.forName(ServerLevel.class.getName(), true, loader);
            Class.forName("net.minecraft.server.level.ChunkMap", true, loader);
            Class.forName("net.minecraft.server.level.GenerationChunkHolder", true, loader);
            Class.forName("net.minecraft.server.level.ServerChunkCache$MainThreadExecutor", true, loader);
            Class.forName("net.minecraft.world.level.chunk.storage.SerializableChunkData", true, loader);
            Class.forName("net.minecraft.world.level.chunk.storage.SectionStorage", true, loader);
            Class.forName("net.minecraft.server.level.ThreadedLevelLightEngine", true, loader);
            Class.forName("net.minecraft.server.level.ChunkTaskDispatcher", true, loader);
            Class.forName("net.minecraft.util.thread.AbstractConsecutiveExecutor", true, loader);
        } catch (ClassNotFoundException impossible) {
            throw new IllegalStateException("required Minecraft lifecycle class is unavailable", impossible);
        }
        for (ServerLevel level : server.getAllLevels()) requireChunkWorld(level);
        if (regions.isEmpty()) throw new IllegalStateException("no server levels available for Stage 2 live binding");
        scheduler = new RegionScheduler(runtimes, directory, barrier, this, this);
        gateway = new OwnerGateway(directory, runtimes, scheduler);
        objectGateway = new ObjectOwnerGateway(objects, directory, gateway);
        chunkGateway = new ChunkCompletionGateway(directory, gateway, chunkCustody);
        regionalDispatcher = new RegionalServiceDispatcher("phoenium-regional-services", lifecycleStarts + 1,
            directory, runtimes, gateway, scheduler);
        regionalServices = new RegionalWorldServices(directory, regionalDispatcher, objects);
        initializeRegionalSaves(regionalDispatcher);
        for (ServerLevel level : server.getAllLevels()) regionalServices.bind(level);
        if (fullEnclosure == null)
            fullEnclosure = new dev.phoenium.chunk.FullRegionalEnclosure(directory, runtimes, gateway, objects, regionalServices);
        else fullEnclosure.installStateMigration(regionalServices);
        regionalDispatcher.installBoundaryAdvancer(fullEnclosure::advance);
        regionalDispatcher.installClosureEncloser(fullEnclosure::enclose);
        state = State.RUNNING;
        ChunkRuntimeTrace.start();
        for (ServerLevel level : server.getAllLevels()) refreshEntityBindings(level);
        try (var turn = dev.phoenium.chunk.FullFinishingServices.forServer(server)
                .serial(dev.phoenium.chunk.FullFinishingServices.Phase.SERVER_TASK)) {
            regionalServices.initializeTracking();
        }
        lifecycleStarts++;
        logger.info("PHOENIUM_STAGE3 movement tickCount selector and interaction target selector loaded once; explicitFixture={}", explicitFixture);
        logStatus("started");
    }

    /** Called only from the redirect around MinecraftServer.tickChildren' single ServerLevel.tick invoke. */
    public void dispatchLevelTick(ServerLevel level, BooleanSupplier shouldKeepTicking) {
        intercepts++;
        if (!acceptsGameplay()) {
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
            while (executions < dispatches && scheduler.lifecycle().acceptsSimulationWork()) {
                if (!pendingTick.queued) advanceOwnerFreeTickPhase(pendingTick, live);
                long before = scheduler.advances();
                scheduler.advanceOne();
                if (scheduler.advances() == before) {
                    if (scheduler.lifecycle() == SchedulerLifecycle.FAILED) break;
                    throw new IllegalStateException("admitted live tick was not reachable through the owner scheduler");
                }
            }
        } catch (RuntimeException | Error failure) {
            // Preserve a failed original control-plane prefix; never replay the owned prefix.
            failClosed(failure, pendingTick.envelope());
            scheduler.quiesce();
            throw failure;
        } finally {
            pendingTick = null;
        }
        if (scheduler.lifecycle() == SchedulerLifecycle.FAILED) {
            if (hardBarriers == 0) barrier.record().ifPresent(record ->
                    logger.error("PHOENIUM_STAGE2 live tick failure; simulation remains fenced", record.cause()));
            hardBarriers = 1;
        }
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.LiveLevelTick tick) {
        PendingTick pending = pendingTick;
        if (pending == null || !tick.worldKey().equals(worldId(pending.level()).value())) {
            unleasedExecutions++;
            throw new IllegalStateException("live tick execution was not bound to its admitted envelope");
        }
        if (pending.step == PendingTick.Step.PREFIX_OWNER && injectedFailureAt > 0 && dispatches == injectedFailureAt) {
            throw new IllegalStateException("Stage 2 requested live tick failure at dispatch " + dispatches);
        }
        var phases = (LiveTickPhases.LevelAccess) pending.level();
        if (!pending.queued) throw new IllegalStateException("live tick owner phase was not queued");
        switch (pending.step) {
            case PREFIX_OWNER -> {
                pending.levelState = phases.phoenium$beforeScheduledTicks(pending.shouldKeepTicking());
                pending.step = PendingTick.Step.SCHEDULED_COORDINATOR;
            }
            case MIDDLE_OWNER -> {
                phases.phoenium$beforeChunkTick(pending.levelState);
                pending.step = PendingTick.Step.READINESS_COORDINATOR;
            }
            case CACHE_OWNER -> {
                if (pending.readiness == null || !pending.readiness.current())
                    throw new IllegalStateException("live chunk phase has no current original readiness");
                phases.phoenium$afterChunkTick(pending.levelState, pending.cacheState);
                pending.step = PendingTick.Step.BLOCK_EVENTS_COORDINATOR;
            }
            case FINAL_OWNER -> {
                phases.phoenium$finishLevelTick(pending.levelState);
                refreshEntityBindings(pending.level());
                executions++;
                runtime.tick();
                pending.step = PendingTick.Step.DONE;
            }
            default -> throw new IllegalStateException("regional coordinator phase reached owner scheduler " + pending.step);
        }
        pending.queued = false;
    }

    private void advanceOwnerFreeTickPhase(PendingTick pending, LiveRegion live) {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("tick coordinator retained owner authority");
        var phases = (LiveTickPhases.LevelAccess) pending.level();
        switch (pending.step) {
            case SCHEDULED_COORDINATOR -> {
                phases.phoenium$runScheduledTicks(pending.levelState);
                queueTickPhase(pending, live, PendingTick.Step.MIDDLE_OWNER);
            }
            case READINESS_COORDINATOR -> {
                if (pending.cacheState == null)
                    pending.cacheState = ((LiveTickPhases.CacheAccess) pending.levelState.cache())
                        .phoenium$prepareChunkTick(pending.shouldKeepTicking(), true);
                pending.readiness = LiveTickReadiness.await(pending.level());
                queueTickPhase(pending, live, PendingTick.Step.CACHE_OWNER);
            }
            case BLOCK_EVENTS_COORDINATOR -> {
                LiveTickPhases.finishCacheMaintenance(pending.levelState.cache(), pending.cacheState);
                pending.levelState.finishMaintenance(pending.level());
                phases.phoenium$runBlockEvents(pending.levelState);
                queueTickPhase(pending, live, PendingTick.Step.FINAL_OWNER);
            }
            default -> throw new IllegalStateException("owner-free tick coordinator reached " + pending.step);
        }
    }

    private void queueTickPhase(PendingTick pending, LiveRegion live, PendingTick.Step step) {
        RegionRoute route = directory.resolve(live.key()).orElseThrow();
        long currentSequence = ++sequence;
        RegionTaskEnvelope continuation = new RegionTaskEnvelope("live-level-tick-" + step.name().toLowerCase(),
            new TaskPayload.LiveLevelTick(worldId(pending.level()).value()), live.key(), route.generation(), route.ownerEpoch(),
            "minecraft-server", new TaskCorrelationId(currentSequence), new TaskSequence(currentSequence), AdmissionPolicy.REJECT);
        if (gateway.submit(continuation) != OwnerGateway.Delivery.QUEUED)
            throw new IllegalStateException("live tick phase admission rejected for " + worldId(pending.level()).value() + " / " + step);
        pending.envelope = continuation;
        pending.step = step;
        pending.queued = true;
    }

    /** New packet/tick demand stops first; original generation and save continuations keep
     * their existing admission until vanilla has drained and closed the worlds. */
    public void closeGameplayAdmission() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("shutdown admission changed inside an owner callback");
        gameplayAdmissionClosed = true;
    }

    public void drainAdmittedWork(MinecraftServer server) {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("shutdown drain retained regional authority");
        if (!gameplayAdmissionClosed) throw new IllegalStateException("shutdown drain before gameplay admission closed");
        if (scheduler == null) return;
        if (pendingTick != null) throw new IllegalStateException("shutdown interrupted an unfinished original world tick");
        server.managedBlock(() -> {
            requireHealthySettlement();
            pumpFullOne();
            chunkBinding.pumpOne();
            pumpSaveOne();
            scheduler.advanceOne();
            requireHealthySettlement();
            return packets.isEmpty() && chunkRequests.isEmpty()
                    && (regionalDispatcher == null || regionalDispatcher.pendingCount() == 0)
                    && (regionalSaves == null || regionalSaves.pending() == 0)
                    && runtimes.values().stream().allMatch(runtime -> runtime.inboxSize() == 0 && runtime.transitionMailboxSize() == 0);
        });
    }

    private void requireHealthySettlement() {
        Throwable failure = barrier.record().map(record -> record.cause()).orElse(null);
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("shutdown cannot execute past the shared failure barrier", failure);
        if (!scheduler.lifecycle().acceptsSimulationWork())
            throw new IllegalStateException("shutdown drain reached a stopped scheduler");
    }

    public void stop() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("runtime cleanup retained regional authority");
        if (state == State.STOPPED || settlingRuntime) return;
        settlingRuntime = true;
        closeGameplayAdmission();
        var failures = new dev.phoenium.chunk.FullInputNotifications.Failures();
        try {
            failures.run(() -> {
                if (regionalSaves == null) return;
                if (barrier.tripped()) {
                    if (serverInstance != null) serverInstance.managedBlock(() -> {
                        regionalSaves.acknowledgeAfterBarrier(barrier);
                        return regionalSaves.pending() == 0;
                    });
                    else regionalSaves.acknowledgeAfterBarrier(barrier);
                } else if (serverInstance != null) dev.phoenium.save.RegionalSaveBinding.flush(serverInstance, regionalSaves);
                regionalSaves.requireFlushed();
            });
            for (var full : java.util.List.copyOf(fullRegistrations.values())) failures.run(full::shutdown);
            failures.run(() -> logger.info("PHOENIUM_FULL STOPPED {}", fullDiagnostics()));
            failures.run(chunkBinding::shutdown);
            failures.run(() -> { if (scheduler != null) { scheduler.quiesce(); scheduler.stop(); } });
            failures.run(() -> {
                int abandoned = regionalDispatcher == null ? 0 : regionalDispatcher.abandonPending();
                int packetCount = packets.size(); packets.clear();
                for (var request : java.util.List.copyOf(chunkRequests.values())) {
                    chunkCustody.cancel(ChunkCustody.id(request));
                    chunkRequests.remove(request.correlationId());
                }
                int envelopes = scheduler == null ? 0 : scheduler.acknowledgeStoppedEnvelopes();
                if (abandoned != 0 || packetCount != 0 || envelopes != 0)
                    throw new IllegalStateException("shutdown acknowledged unexecuted work after failure: services=" + abandoned
                            + " packets=" + packetCount + " envelopes=" + envelopes);
            });
            for (var services : java.util.List.copyOf(chunkPrerequisiteServices.values())) failures.run(services::shutdown);
            failures.run(() -> { if (regionalServices != null) regionalServices.unbindAll(); });
        } finally {
            chunkPrerequisiteServices.clear();
            fullRegistrations.clear();
            state = State.STOPPED;
            lifecycleStops++;
            settlingRuntime = false;
            failures.run(() -> logger.info("PHOENIUM_STAGE4_SHUTDOWN admissionClosed={} queuedServices={} pendingSaves={} saveState={}",
                    gameplayAdmissionClosed, regionalDispatcher == null ? 0 : regionalDispatcher.pendingCount(),
                    regionalSaves == null ? 0 : regionalSaves.pending(), regionalSaves == null ? java.util.Map.of() : regionalSaves.measurements()));
            failures.run(() -> logStatus("stopped"));
            failures.run(ChunkRuntimeTrace::finish);
        }
        failures.rethrow();
    }

    public LiveDiagnostics diagnostics() {
        int activeLeases = runtimes.values().stream().mapToInt(runtime -> runtime.leases().hasActiveLease() ? 1 : 0).sum();
        return new LiveDiagnostics(state, scheduler == null ? null : scheduler.lifecycle(), lifecycleStarts, lifecycleStops, 1, false,
            regions.size(), activeLeases, intercepts, dispatches, executions, duplicates, unexpectedBypasses, fallbacks,
            ownershipViolations, unleasedExecutions, hardBarriers, barrier.tripped(), objects.liveCount(), objects.playerCount(),
            packetAdmissions, packetExecutions, packetStaleReroutes, packetRejections, packetRecursions,
            chunkCompletionAdmissions, chunkCompletionApplications, chunkCompletionReroutes, chunkCompletionRejections, chunkCompletionDuplicates,
            producerThreadChunkMutations);
    }
    public LivePacketDiagnostics packetDiagnostics() {
        return new LivePacketDiagnostics(movementIngress, movementBootstrapDeferred, movementFootprintAccepted, movementFootprintRejected,
            movementOwnerExecution, movementReplayCount, movementHandlerCompletion, movementRoutingReconciliation,
            movementSameOwnerEpochPreserved, movementStaleTicketRejected, interactionIngress, interactionIndexHit,
            interactionIndexMiss, interactionIncarnationRejected, interactionOwnerAdmission, interactionCrossOwnerRejected,
            interactionOwnerExecution, interactionReplay, interactionVanillaLookup, interactionHandlerCompletion,
            interactionStaleTicketRejected);
    }
    public String failureDescription() {
        return barrier.record().map(record -> record.cause().toString()).orElse("");
    }

    public ChunkBindingRuntime chunkBinding() { return chunkBinding; }
    public RegionalWorldServices regionalServices() {
        if (regionalServices == null) throw new IllegalStateException("regional services unavailable before live start");
        return regionalServices;
    }
    public RegionalServiceDispatcher regionalDispatcher() {
        if (regionalDispatcher == null) throw new IllegalStateException("regional dispatcher unavailable before live start");
        return regionalDispatcher;
    }
    public void initializeRegionalSaves(RegionalServiceDispatcher dispatcher) {
        if (regionalSaves != null) throw new IllegalStateException("regional save coordinator initialized twice");
        regionalSaves = new dev.phoenium.save.RegionalSaveCoordinator(directory, runtimes, dispatcher);
    }
    public dev.phoenium.save.RegionalSaveCoordinator regionalSaves() {
        // Null only before the original live-start service installation boundary.
        return regionalSaves;
    }
    public boolean pumpSaveOne() {
        if (OwnershipContext.current().isPresent() || dev.phoenium.chunk.FullFinishingServices.finishing()
                || regionalSaves == null || !regionalSaves.hasAcknowledgement()) return false;
        try (var turn = dev.phoenium.chunk.FullFinishingServices.forServer(serverInstance)
                .serial(dev.phoenium.chunk.FullFinishingServices.Phase.SERVER_TASK)) {
            return regionalSaves.pumpOne();
        }
    }

    public <T> void regionalTickContainerAdded(LevelTicks<T> owner, ChunkPos pos, LevelChunkTicks<T> container) {
        if (regionalServices != null) regionalServices.containerAdded(owner, pos, container);
    }
    public <T> void regionalTickContainerRemoved(LevelTicks<T> owner, ChunkPos pos, LevelChunkTicks<T> container) {
        if (regionalServices != null) regionalServices.containerRemoved(owner, pos, container);
    }
    public <T> void regionalTickScheduled(LevelTicks<T> owner, long chunkKey, LevelChunkTicks<T> container) {
        if (regionalServices != null) regionalServices.scheduled(owner, chunkKey, container);
    }
    public <T> boolean scheduleRegionalTick(LevelTicks<T> owner, net.minecraft.world.ticks.ScheduledTick<T> tick) {
        return regionalServices != null && regionalServices.schedule(owner, tick);
    }
    public <T> boolean mutateRegionalTickArea(LevelTicks<T> owner, LevelTicks<T> source,
            net.minecraft.world.level.levelgen.structure.BoundingBox area, net.minecraft.core.Vec3i offset, Runnable original) {
        return regionalServices != null && regionalServices.mutateTickArea(owner, source, area, offset, original);
    }
    public <T> Boolean regionalWillTickThisTick(LevelTicks<T> owner, BlockPos pos, T type) {
        return regionalServices == null ? null : regionalServices.willTickThisTick(owner, pos, type);
    }
    public <T> boolean runRegionalScheduledTicks(LevelTicks<T> owner, long currentTick, int maxTicks, BiConsumer<BlockPos, T> output) {
        return regionalServices != null && regionalServices.runScheduled(owner, currentTick, maxTicks, output);
    }
    public boolean enqueueRegionalBlockEvent(ServerLevel level, BlockEventData event) {
        return regionalServices != null && regionalServices.enqueueBlockEvent(level, event);
    }
    public boolean runRegionalBlockEvents(ServerLevel level) {
        return regionalServices != null && regionalServices.runBlockEvents(level);
    }
    public boolean runRegionalTracking(net.minecraft.server.level.ChunkMap map) {
        return regionalServices != null && regionalServices.runTracking(map);
    }
    public boolean regionalTrackingAdd(net.minecraft.server.level.ChunkMap map, Entity entity, Runnable original) {
        return regionalServices != null && regionalServices.addTrackingEntity(map, entity, original);
    }
    public boolean regionalTrackingAddPlayers(net.minecraft.server.level.ChunkMap map, Object tracker,
                                              java.util.List<ServerPlayer> players) {
        return regionalServices != null && regionalServices.addTrackingPlayers(map, tracker, players);
    }
    public boolean regionalTrackingAddPlayer(net.minecraft.server.level.ChunkMap map, Object tracker,
                                             ServerPlayer player, Runnable original) {
        return regionalServices != null && regionalServices.addTrackingPlayer(map, tracker, player, original);
    }
    public boolean regionalTrackingRemove(net.minecraft.server.level.ChunkMap map, Entity entity, Runnable original) {
        return regionalServices != null && regionalServices.removeTrackingEntity(map, entity, original);
    }
    public boolean regionalTrackingRemovePlayer(net.minecraft.server.level.ChunkMap map, Object tracker,
                                                ServerPlayer player, Runnable original) {
        return regionalServices != null && regionalServices.removeTrackingPlayer(map, tracker, player, original);
    }
    public boolean regionalTrackingMove(net.minecraft.server.level.ChunkMap map, ServerPlayer player, Runnable original) {
        return regionalServices != null && regionalServices.moveTrackingPlayer(map, player, original);
    }
    public boolean regionalTrackingMovedPlayers(net.minecraft.server.level.ChunkMap map, Object tracker,
                                                java.util.List<ServerPlayer> players) {
        return regionalServices != null && regionalServices.movedTrackingPlayers(map, tracker, players);
    }
    public boolean regionalTrackingMovedPlayer(net.minecraft.server.level.ChunkMap map, Object tracker,
                                               ServerPlayer player, Runnable original) {
        return regionalServices != null && regionalServices.movedTrackingPlayer(map, tracker, player, original);
    }
    public void regionalTrackingSourceAdded(net.minecraft.server.level.ChunkMap map, Entity entity) {
        if (regionalServices != null) regionalServices.trackingSourceAdded(map, entity);
    }

    public void regionalTrackingSourceRemoved(net.minecraft.server.level.ChunkMap map, Entity entity) {
        if (regionalServices != null) regionalServices.trackingSourceRemoved(map, entity);
    }
    private final boolean authoritativeFull = Boolean.getBoolean("phoenium.full.authoritative");
    private final java.util.Map<ServerLevel, dev.phoenium.chunk.FullRegistrationRuntime> fullRegistrations = new java.util.concurrent.ConcurrentHashMap<>();
    private dev.phoenium.chunk.FullRegionalEnclosure fullEnclosure;
    public dev.phoenium.chunk.FullRegistrationRuntime fullRegistration(ServerLevel level) {
        if (!authoritativeFull) return null;
        return fullRegistrations.computeIfAbsent(level, world -> {
            var authority = requireChunkWorld(world);
            if (fullEnclosure == null) {
                // The coordinator shares the existing runtime/lease/object ledgers. Bootstrap
                // has no queued owner work; this gateway is only its mailbox revalidator.
                var topologyScheduler = new RegionScheduler(runtimes, directory, barrier, this, this);
                var topologyGateway = new OwnerGateway(directory, runtimes, topologyScheduler);
                fullEnclosure = regionalServices == null
                    ? new dev.phoenium.chunk.FullRegionalEnclosure(directory, runtimes, topologyGateway, objects)
                    : new dev.phoenium.chunk.FullRegionalEnclosure(directory, runtimes, topologyGateway, objects, regionalServices);
                if (regionalDispatcher != null) regionalDispatcher.installBoundaryAdvancer(fullEnclosure::advance);
            }
            return new dev.phoenium.chunk.FullRegistrationRuntime(world, authority,
                    dev.phoenium.chunk.FullInitializedInputs.Limits.production(), fullEnclosure);
        });
    }
    public boolean pumpFullOne() {
        if (dev.phoenium.ownership.OwnershipContext.current().isPresent()) return false;
        for (var full : fullRegistrations.values()) if (full.pumpOne()) return true;
        return false;
    }
    public String fullDiagnostics() {
        return "authoritative=" + authoritativeFull + " " + fullRegistrations.entrySet().stream()
                .map(entry -> entry.getKey().dimension().identifier() + "=" + entry.getValue().measurements()
                        + " inputs=" + entry.getValue().inputs.measurements() + " published=" + entry.getValue().published.measurements())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    @Override
    public ChunkBindingRuntime.WorldAuthority requireChunkWorld(ServerLevel level) {
        Objects.requireNonNull(level);
        if (state == State.STOPPED) throw new IllegalStateException("chunk world requested after shutdown");
        WorldId world = worldId(level);
        LiveRegion existing = regions.get(world);
        if (existing != null) {
            if (existing.level() != level) throw new IllegalStateException("world instance changed without shutdown " + world.value());
            return chunkAuthority(world);
        }

        explicitFixture = ExplicitLiveTopologyFixture.enabled();
        TopologyGeneration generation = new TopologyGeneration(directory.snapshot().generation().value() + 1);
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
        directory.snapshot().routes().forEach((key, route) -> routes.put(key,
                new RegionRoute(route.region(), generation, route.ownerEpoch())));
        Map<WorldId, RegionRoute> defaults = new LinkedHashMap<>();
        directory.snapshot().worldDefaults().forEach((key, route) -> defaults.put(key,
                new RegionRoute(route.region(), generation, route.ownerEpoch())));

        RoutingKey key = new RoutingKey(world, 0, 0);
        RegionId region;
        RegionRuntime runtime;
        if (explicitFixture) {
            RoutingKey initialSpawn = null;
            if (Boolean.getBoolean("phoenium.test.chunkFixtureSupport")) {
                var position = net.minecraft.world.level.ChunkPos.containing(
                        level.getChunkSource().randomState().sampler().findSpawnPosition());
                initialSpawn = new RoutingKey(world, position.x(), position.z());
            }
            ExplicitLiveTopologyFixture.Plan plan = ExplicitLiveTopologyFixture.plan(world, key, generation,
                    new OwnerEpoch(0), new OwnerEpoch(0), initialSpawn);
            region = plan.a(); runtime = readyRuntime(region);
            RegionRuntime b = readyRuntime(plan.b());
            runtimes.put(plan.b(), b); routes.putAll(plan.routes());
        } else {
            region = new RegionId(world, "stage2-live-world"); runtime = readyRuntime(region);
            defaults.put(world, new RegionRoute(region, generation, runtime.ownerEpoch()));
        }
        long worldInstanceSequence = lifecycleStarts + 1;
        if (regions.put(world, new LiveRegion(level, region, key, runtime, worldInstanceSequence)) != null)
            throw new IllegalStateException("duplicate controlled world " + world.value());
        runtimes.put(region, runtime);
        chunkPrerequisiteServices.put(world,
                new ChunkPrerequisiteServices(world, "live-" + worldInstanceSequence, worldInstanceSequence));
        directory.publish(generation, routes, defaults);
        return chunkAuthority(world);
    }

    private ChunkBindingRuntime.WorldAuthority chunkAuthority(WorldId world) {
        Map<RegionId, dev.phoenium.ownership.RegionLeaseManager> leases = new LinkedHashMap<>();
        runtimes.forEach((id, runtime) -> { if (id.world().equals(world)) leases.put(id, runtime.leases()); });
        LiveRegion region = Objects.requireNonNull(regions.get(world));
        return new ChunkBindingRuntime.WorldAuthority(world, region.worldInstanceSequence(),
                Objects.requireNonNull(chunkPrerequisiteServices.get(world)), directory, leases, barrier);
    }

    @Override
    public void chunkBarrier(Throwable failure) {
        if (!barrier.tripped()) {
            barrier.trip(failure, directory.snapshot().generation(), new OwnerEpoch(0), null);
            hardBarriers = 1;
        }
    }

    /** Test-only route evidence reads the immutable directory; it grants no gameplay authority. */
    public String routeDiagnostic(String worldName, int x, int z) {
        WorldId world = new WorldId(worldName);
        RoutingKey key = new RoutingKey(world, x, z);
        RegionRoute route = directory.resolve(key).orElse(null);
        if (route == null) {
            return "route=UNRESOLVED world=" + world.value() + " chunk=" + x + "," + z
                + " generation=" + directory.snapshot().generation().value()
                + " explicitFixture=" + explicitFixture;
        }
        return "route=RESOLVED world=" + world.value() + " chunk=" + x + "," + z
            + " region=" + route.region().value() + " generation=" + route.generation().value()
            + " ownerEpoch=" + route.ownerEpoch().value() + " explicitFixture=" + explicitFixture;
    }

    /**
     * Snapshot used only by the gated chunk trace.  A context is evidence of an active lease;
     * neither the route nor the thread identity grants authority to the observer.
     */
    public ChunkRuntimeTrace.RouteContext chunkTraceRoute(String worldName, int x, int z) {
        WorldId world = new WorldId(worldName);
        RegionRoute route = directory.resolve(new RoutingKey(world, x, z)).orElse(null);
        var active = OwnershipContext.current();
        boolean hasContext = active.isPresent();
        String contextRegion = active.map(value -> value.region().value()).orElse(null);
        long leaseEpoch = active.map(value -> value.lease().value()).orElse(-1L);
        if (route == null) {
            return new ChunkRuntimeTrace.RouteContext(false, "UNRESOLVED", -1, -1,
                hasContext, hasContext, contextRegion, leaseEpoch);
        }
        return new ChunkRuntimeTrace.RouteContext(true, route.region().value(), route.generation().value(),
            route.ownerEpoch().value(), hasContext, hasContext, contextRegion, leaseEpoch);
    }

    /** Re-entry is valid only for the exact correlation, connection, and packet object currently replaying. */
    public boolean matchesMoveReplay(ServerGamePacketListenerImpl connection, ServerboundMovePlayerPacket packet) {
        ReplayIdentity active = replayIdentity.get();
        boolean match = active != null && active.kind == ReplayKind.MOVE && active.connection == connection && active.packet == packet;
        if (match) movementReplayCount++;
        return match;
    }
    public boolean matchesInteractReplay(ServerGamePacketListenerImpl connection, ServerboundInteractPacket packet) {
        ReplayIdentity active = replayIdentity.get();
        boolean match = active != null && active.kind == ReplayKind.INTERACT && active.connection == connection && active.packet == packet;
        if (match) interactionVanillaLookup++;
        return match;
    }

    /** Called at the required valid-input seam, before resetPosition, jumping, or player movement. */
    public void admitMovePacket(ServerGamePacketListenerImpl connection, ServerboundMovePlayerPacket packet,
                                double lastGoodX, double lastGoodY, double lastGoodZ, boolean awaitingTeleport) {
        movementIngress++;
        if (!acceptsGameplay()) { packetRejections++; return; }
        ServerPlayer player = connection.player;
        if (player == null) { packetRejections++; movementFootprintRejected++; return; }
        ObjectOwnership current = bindPlayer(player);
        LiveMovementSnapshot snapshot = LiveMovementSnapshot.capture(player, packet, lastGoodX, lastGoodY, lastGoodZ, awaitingTeleport);
        if (!snapshot.classified()) {
            if (MovementBootstrapDisposition.from(snapshot.disposition()) == MovementBootstrapDisposition.DEFER_UNTIL_TELEPORT_OR_BASELINE_CONVERGES) {
                movementBootstrapDeferred++;
            } else {
                packetRejections++; movementFootprintRejected++;
            }
            return;
        }
        try {
            LiveMovementAdmission.Ticket ticket = LiveMovementAdmission.admit(directory, current.owner(), snapshot.footprint());
            movementFootprintAccepted++;
            admitPacket(connection, player, new PendingMove(this, connection, packet, current.objectId(), current.incarnation(), ticket),
                "player-movement", current, AdmissionPolicy.REJECT);
        } catch (RuntimeException rejected) { packetRejections++; movementFootprintRejected++; }
    }

    /** Resolves an immutable derived target reference, without calling ServerLevel.getEntityOrPart. */
    public void admitInteractPacket(ServerGamePacketListenerImpl connection, ServerboundInteractPacket packet) {
        interactionIngress++;
        if (!acceptsGameplay()) { packetRejections++; return; }
        ServerPlayer player = connection.player;
        if (player == null) { packetRejections++; return; }
        ObjectOwnership playerBinding = bindPlayer(player);
        try {
            InteractionTargetRef target = interactionTargets.require(worldId(player.level()), packet.entityId());
            interactionIndexHit++;
            LiveInteractionAdmission.Ticket ticket = LiveInteractionAdmission.admit(directory, playerBinding, target, interactionTargets);
            interactionOwnerAdmission++;
            admitPacket(connection, player, new PendingInteract(connection, packet, ticket), "entity-interaction", playerBinding, AdmissionPolicy.REJECT);
        } catch (InteractionTargetIndex.UnknownInteractionTargetException rejected) { packetRejections++; interactionIndexMiss++; }
        catch (LiveInteractionAdmission.CrossRegionInteractionException rejected) { packetRejections++; interactionCrossOwnerRejected++; }
        catch (RuntimeException rejected) {
            packetRejections++;
            interactionIncarnationRejected++;
            logger.warn("PHOENIUM_STAGE3 interaction admission rejected entityId={} reason={}", packet.entityId(), rejected.toString());
        }
    }

    /**
     * The base 26.2 ServerChunkCache future is observed, not replaced. Its completion thread holds
     * only immutable request metadata and queues an owner envelope; it never receives a LevelChunk.
     */
    public void observeChunkFuture(ServerLevel level, int x, int z, ChunkStatus status,
                                   CompletableFuture<ChunkResult<ChunkAccess>> future) {
        if (!acceptsGameplay()) return;
        WorldId world = worldId(level);
        if (!observedChunkFutureWorlds.add(world)) return;
        TaskCorrelationId correlation = new TaskCorrelationId(++sequence);
        ChunkWorkRequest request = new ChunkWorkRequest(world, new RoutingKey(world, x, z), sequence,
            directory.snapshot().generation(), "base-server-chunk-future-" + status.getName(), correlation);
        chunkRequests.put(correlation, request);
        chunkCustody.request(ChunkCustody.id(request));
        future.whenComplete((ignoredResult, failure) -> {
            if (failure != null) {
                // The producer failed before completion: the custody ledger records the failure and
                // the request identity is dropped. No owner work is ever fabricated for it.
                chunkCustody.fail(ChunkCustody.id(request));
                chunkRequests.remove(request.correlationId());
                return;
            }
            admitChunkCompletion(request);
        });
    }

    private void admitChunkCompletion(ChunkWorkRequest request) {
        if (!isRunning()) { chunkCustody.cancel(ChunkCustody.id(request)); chunkRequests.remove(request.correlationId()); return; }
        ChunkCompletionGateway.Delivery delivery = chunkGateway.complete(request);
        if (delivery == ChunkCompletionGateway.Delivery.QUEUED || delivery == ChunkCompletionGateway.Delivery.REROUTED_CURRENT_OWNER) {
            chunkCompletionAdmissions++;
            if (delivery == ChunkCompletionGateway.Delivery.REROUTED_CURRENT_OWNER) chunkCompletionReroutes++;
        } else {
            chunkRequests.remove(request.correlationId());
            chunkCompletionRejections++;
        }
    }

    private void admitPacket(ServerGamePacketListenerImpl connection, ServerPlayer player, PendingPacket packet,
                             String operationKind, ObjectOwnership prepared, AdmissionPolicy policy) {
        if (!acceptsGameplay() || player == null) { packetRejections++; return; }
        ObjectOwnership binding = prepared == null ? bindPlayer(player) : prepared;
        TaskCorrelationId correlation = new TaskCorrelationId(++sequence);
        packets.put(correlation, packet);
        ObjectOwnerGateway.Delivery delivery = objectGateway.submit(new OwnedObjectEnvelope(binding.objectId(), binding.objectEpoch(), operationKind,
            "minecraft-packet", correlation, new TaskSequence(sequence), policy));
        if (delivery == ObjectOwnerGateway.Delivery.QUEUED || delivery == ObjectOwnerGateway.Delivery.REROUTED) {
            packetAdmissions++;
            if (delivery == ObjectOwnerGateway.Delivery.REROUTED) packetStaleReroutes++;
        } else {
            packets.remove(correlation);
            packetRejections++;
        }
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
        ObjectOwnership executingPlayer = objects.requireCurrentOwner(operation.objectId(), operation.expectedObjectEpoch(), runtime.id());
        PendingPacket packet = packets.remove(operation.correlationId());
        if (packet == null) throw new IllegalStateException("missing admitted packet " + operation.correlationId());
        if (packet instanceof PendingMove move) {
            movementOwnerExecution++;
            try {
                if (executingPlayer.incarnation() != move.playerIncarnation()) throw new IllegalStateException("stale movement player incarnation");
                move.ticket().requireCurrent(directory, runtime.id());
            } catch (RuntimeException stale) { movementStaleTicketRejected++; return; }
            try (LivePacketReplayGate.Scope gate = movementReplay.enter(move.ticket(), directory, runtime.id());
                 PacketExecutionScope ignored = enterPacketExecution(operation.correlationId(), ReplayKind.MOVE, move.connection(), move.packet())) {
                move.execute();
                packetExecutions++;
                movementHandlerCompletion++;
            }
            return;
        }
        if (packet instanceof PendingInteract interact) {
            interactionOwnerExecution++;
            try {
                interact.ticket().requireCurrent(directory, objects, interactionTargets, runtime.id());
                if (!consumedInteractionReplays.add(operation.correlationId())) throw new IllegalStateException("interaction replay ticket already consumed");
            } catch (RuntimeException stale) { interactionStaleTicketRejected++; return; }
            try (PacketExecutionScope ignored = enterPacketExecution(operation.correlationId(), ReplayKind.INTERACT, interact.connection(), interact.packet())) {
                interact.execute();
                packetExecutions++;
                interactionReplay++;
                interactionHandlerCompletion++;
            }
            return;
        }
        throw new IllegalStateException("unknown admitted packet");
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.ChunkCompletion completion) {
        ChunkWorkRequest request = chunkRequests.get(completion.correlationId());
        if (request == null || request.requestGeneration() != completion.requestGeneration()) {
            chunkRequests.remove(completion.correlationId());
            throw new IllegalStateException("missing or stale chunk completion " + completion.correlationId());
        }
        ChunkCustody.CustodyId custodyId = ChunkCustody.id(request);
        RegionRoute current = directory.resolve(request.chunkKey()).orElse(null);
        if (current == null) {
            // The key no longer resolves at this quiescent boundary. Drop the completion without
            // fabricating an EmptyChunk/AIR/BEDROCK state: stale ownership is not a disk failure.
            chunkRequests.remove(completion.correlationId());
            chunkCustody.cancel(custodyId);
            return;
        }
        if (!current.region().equals(runtime.id())) throw new IllegalStateException("chunk completion reached foreign owner");
        if (!chunkCustody.claim(custodyId, runtime.id())) {
            // The claim gate is the exactly-once application boundary; a second claim is duplicate evidence.
            chunkCompletionDuplicates++;
            return;
        }
        chunkRequests.remove(completion.correlationId());
        chunkCustody.apply(custodyId);
        // This observer has no base-game result-application privilege; the leased owner records only a validated completion admission.
        chunkCompletionApplications++;
    }

    private PacketExecutionScope enterPacketExecution(TaskCorrelationId correlation, ReplayKind kind,
                                                      ServerGamePacketListenerImpl connection, Object packet) {
        if (replayIdentity.get() != null) { packetRecursions++; throw new IllegalStateException("nested packet replay"); }
        ReplayIdentity marker = new ReplayIdentity(correlation, kind, connection, packet);
        replayIdentity.set(marker);
        return new PacketExecutionScope(marker);
    }

    private void failClosed(Throwable failure, RegionTaskEnvelope envelope) {
        if (!barrier.tripped()) {
            barrier.trip(failure, directory.snapshot().generation(), new OwnerEpoch(0), envelope);
            hardBarriers = 1;
        }
    }
    private boolean isRunning() { return state == State.RUNNING && scheduler != null && scheduler.lifecycle().acceptsSimulationWork(); }
    private boolean acceptsGameplay() { return !gameplayAdmissionClosed && isRunning(); }
    private static WorldId worldId(ServerLevel level) { return new WorldId(level.dimension().identifier().toString()); }
    private static RegionRuntime readyRuntime(RegionId region) {
        RegionRuntime runtime = new RegionRuntime(region, new OwnerEpoch(0), false);
        runtime.transitionTo(RegionLifecycle.READY);
        return runtime;
    }
    /** Lifecycle callback after vanilla has made an entity addressable; it never performs a world lookup. */
    public void onEntityAddressable(Entity entity) {
        if (!isRunning() || !(entity.level() instanceof ServerLevel level) || entity.isRemoved()) return;
        WorldId world = worldId(level);
        RoutingKey key = new RoutingKey(world, entity.chunkPosition().x(), entity.chunkPosition().z());
        try {
            ObjectOwnership binding = entityIndexLifecycle.addressable(entity.getUUID().toString(),
                entity instanceof ServerPlayer ? OwnedObjectKind.PLAYER : OwnedObjectKind.ENTITY,
                world, entity.getId(), key, explicitFixture);
        } catch (RuntimeException unavailable) {
            // A target outside the finite explicit fixture has no derived candidate.  It cannot be
            // rediscovered from the ServerLevel index at packet ingress.
        }
    }

    /** A movement observation may refresh same-owner routing, but a cross-owner entity is invalidated, not transferred. */
    public void onEntityRoutingObserved(Entity entity) {
        if (!isRunning() || !(entity.level() instanceof ServerLevel level) || entity.isRemoved()) return;
        WorldId world = worldId(level);
        RoutingKey key = new RoutingKey(world, entity.chunkPosition().x(), entity.chunkPosition().z());
        entityIndexLifecycle.routingObserved(entity.getUUID().toString(), world, entity.getId(), key);
    }

    /** Removal invalidates the network-id candidate before vanilla can later reuse its numeric ID. */
    public void onEntityRemoved(Entity entity) {
        if (!isRunning() || !(entity.level() instanceof ServerLevel level)) return;
        entityIndexLifecycle.removed(entity.getUUID().toString(), worldId(level), entity.getId());
    }

    /** PlayerList placement is the required initial-ownership point before the first movement packet. */
    public void onPlayerPlaced(ServerPlayer player) {
        if (!isRunning()) return;
        bindPlayer(player);
    }

    /** Lease-bound reconciliation remains a startup/audit backstop, not packet-ingress target discovery. */
    private void refreshEntityBindings(ServerLevel level) {
        WorldId world = worldId(level);
        HashSet<String> present = new HashSet<>();
        for (Entity entity : level.getAllEntities()) {
            String id = entity.getUUID().toString();
            present.add(id);
            onEntityAddressable(entity);
        }
        for (var previous : objects.ownedInWorld(world)) if (!present.contains(previous.objectId())) {
            Integer entityId = entityIndexLifecycle.entityId(previous.objectId());
            if (entityId != null) interactionTargets.invalidate(world, entityId, previous.objectId(), previous.incarnation());
            objects.remove(previous.objectId());
        }
    }
    private ObjectOwnership bindPlayer(ServerPlayer player) {
        WorldId world = worldId(player.level());
        String id = player.getUUID().toString();
        RoutingKey key = new RoutingKey(world, player.chunkPosition().x(), player.chunkPosition().z());
        return entityIndexLifecycle.addressable(id, OwnedObjectKind.PLAYER, world, player.getId(), key, explicitFixture);
    }
    private void logStatus(String event) {
        logger.info("PHOENIUM_STAGE2 {} {} packets={}", event, diagnostics().toLogLine(), packetDiagnostics().toLogLine());
    }

    private sealed interface PendingPacket permits PendingMove, PendingInteract {
        void execute();
    }
    private record PendingMove(PhoeniumLiveRuntime runtime, ServerGamePacketListenerImpl connection, ServerboundMovePlayerPacket packet,
                               String objectId, long playerIncarnation, LiveMovementAdmission.Ticket ticket) implements PendingPacket {
        @Override public void execute() {
            ((ServerGamePacketListenerInvoker) connection).phoenium$invokeMovePlayer(packet);
            runtime.reconcileAfterMove(objectId, connection.player);
        }
    }
    /**
     * Runs under the owning region lease after vanilla has settled the position. The routing key follows
     * the authoritative result, never the client proposal; a same-owner settle preserves the object epoch
     * and a cross-owner settle fails closed instead of inventing a transfer.
     */
    private void reconcileAfterMove(String objectId, ServerPlayer player) {
        if (player == null) return;
        ObjectOwnership current = objects.current(objectId).orElseThrow(() -> new IllegalStateException("moving object absent " + objectId));
        RoutingKey actual = new RoutingKey(worldId(player.level()), player.chunkPosition().x(), player.chunkPosition().z());
        ObjectOwnership settled = current.routingKey().equals(actual) ? current
            : objects.updateRoutingKey(objectId, actual, current.objectEpoch());
        movementRoutingReconciliation++;
        if (settled.objectEpoch().equals(current.objectEpoch())) movementSameOwnerEpochPreserved++;
    }
    private record PendingInteract(ServerGamePacketListenerImpl connection, ServerboundInteractPacket packet,
                                   LiveInteractionAdmission.Ticket ticket) implements PendingPacket {
        @Override public void execute() { ((ServerGamePacketListenerInvoker) connection).phoenium$invokeInteract(packet); }
    }
    private final class PacketExecutionScope implements AutoCloseable {
        private final ReplayIdentity marker;
        private boolean closed;
        private PacketExecutionScope(ReplayIdentity marker) { this.marker = marker; }
        @Override public void close() {
            if (closed || replayIdentity.get() != marker) throw new IllegalStateException("packet execution marker escaped");
            replayIdentity.remove();
            closed = true;
        }
    }
    private enum ReplayKind { MOVE, INTERACT }
    private record ReplayIdentity(TaskCorrelationId correlation, ReplayKind kind, ServerGamePacketListenerImpl connection, Object packet) { }
    private record LiveRegion(ServerLevel level, RegionId id, RoutingKey key, RegionRuntime runtime,
                              long worldInstanceSequence) { }
    private static final class PendingTick {
        final ServerLevel level;
        final BooleanSupplier shouldKeepTicking;
        RegionTaskEnvelope envelope;
        LiveTickPhases.LevelState levelState;
        LiveTickPhases.CacheState cacheState;
        LiveTickReadiness.Receipt readiness;
        Step step = Step.PREFIX_OWNER;
        boolean queued = true;
        PendingTick(ServerLevel level, BooleanSupplier shouldKeepTicking, RegionTaskEnvelope envelope) {
            this.level = level; this.shouldKeepTicking = shouldKeepTicking; this.envelope = envelope;
        }
        ServerLevel level() { return level; }
        BooleanSupplier shouldKeepTicking() { return shouldKeepTicking; }
        RegionTaskEnvelope envelope() { return envelope; }
        enum Step { PREFIX_OWNER, SCHEDULED_COORDINATOR, MIDDLE_OWNER, READINESS_COORDINATOR,
            CACHE_OWNER, BLOCK_EVENTS_COORDINATOR, FINAL_OWNER, DONE }
    }
    public enum State { NEW, RUNNING, STOPPED }
}
