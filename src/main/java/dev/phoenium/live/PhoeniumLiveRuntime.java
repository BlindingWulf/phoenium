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
import dev.phoenium.chunk.ChunkProtocolException;
import dev.phoenium.chunk.FullEntityFacts;
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
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.service.RegionalWorldServices;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyPlanner;
import dev.phoenium.topology.ClosureEscapeException;
import dev.phoenium.topology.FrozenRegionAuthority;
import dev.phoenium.topology.LiveMovementAdmission;
import dev.phoenium.topology.LiveBlockPacketAdmission;
import dev.phoenium.topology.LivePlayerPacketAdmission;
import dev.phoenium.topology.LivePacketReplayGate;
import dev.phoenium.interaction.InteractionTargetIndex;
import dev.phoenium.interaction.InteractionTargetRef;
import dev.phoenium.interaction.LiveInteractionAdmission;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.core.BlockPos;
import java.util.function.BiConsumer;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Serial live binding for regional gameplay, chunk lifecycles and world services.
 * World service anchors coordinate native phases; each selected regional callback
 * obtains current route and lease authority through the single gameplay worker.
 */
public final class PhoeniumLiveRuntime implements RegionScheduler.LiveTickExecutor, ObjectOperationExecutor, ChunkBindingRuntime.Authority {
    private static final String PLAYER_ACTION_OPERATION = "player-block-action";
    private static final String USE_ITEM_ON_OPERATION = "player-use-item-on";
    private static final String USE_ITEM_OPERATION = "player-use-item";
    private final Logger logger;
    private final Map<WorldId, LiveRegion> regions = new ConcurrentHashMap<>();
    /** Per-world prerequisite service lifetime; original chunk producers retain exact work identities. */
    private final Map<WorldId, ChunkPrerequisiteServices> chunkPrerequisiteServices = new ConcurrentHashMap<>();
    private final Map<RegionId, RegionRuntime> runtimes = new ConcurrentHashMap<>();
    private final RegionDirectory directory = new RegionDirectory();
    private final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
    private final PearlPortalTransitionManager pearlPortalTransitions;
    private final ItemPortalTransitionManager itemPortalTransitions;
    private final PlayerDimensionTransitionManager playerDimensionTransitions;
    private final Map<TaskCorrelationId, PendingPacket> packets = new ConcurrentHashMap<>();
    /** Movement packets admitted before a native player-level change must retire before replay. */
    private final java.util.Set<TaskCorrelationId> invalidatedMovementPackets = ConcurrentHashMap.newKeySet();
    /**
     * A native dimension change has a short client-baseline convergence window.  During that
     * window, a packet may arrive after the ServerPlayer has entered the destination level while
     * still proposing the old world's coordinates.  Such a proposal must be deferred before it
     * can enter an owner transaction and invoke vanilla collision lookup against an unprepared
     * destination chunk.
     */
    private final Map<String, MovementTransitionFence> movementTransitionFences = new ConcurrentHashMap<>();
    /** Monotonic lifecycle fence closes the admission/replay race around both transition edges. */
    private final Map<String, Long> movementTransitionEpochs = new ConcurrentHashMap<>();
    private final InteractionTargetIndex interactionTargets = new InteractionTargetIndex();
    private final EntityIndexLifecycleAdapter entityIndexLifecycle = new EntityIndexLifecycleAdapter(objects, directory, interactionTargets,
        this::recordEntityRelocation);
    /** Derived pearl association only; current object ownership remains in {@link ObjectOwnershipRegistry}. */
    private final PearlMembershipLedger pearlMemberships = new PearlMembershipLedger();
    /** Derived projectile association only; current object ownership remains in {@link ObjectOwnershipRegistry}. */
    private final ProjectileMembershipLedger projectileMemberships = new ProjectileMembershipLedger();
    private final MobDecisionScheduler mobDecisions = new MobDecisionScheduler(MobDecisionScheduler.Settings.fromProperties());
    /** Exact live aliases retained only for a selected or deferred owner-routed cleanup body. */
    private final Map<PearlMembershipLedger.PearlIdentity, Entity> pearlAliases = new ConcurrentHashMap<>();
    private final Map<TaskCorrelationId, PendingPearlRemoval> pendingPearlRemovals = new ConcurrentHashMap<>();
    /**
     * Cleanup identities whose current object owner is temporarily unavailable while a topology
     * or relocation boundary settles. This is bounded by selected live pearl memberships and is
     * retried at the existing owner-free tick cut; it is not an entity-discovery index.
     */
    private final Map<PearlMembershipLedger.PearlIdentity, DeferredPearlCleanup> deferredPearlCleanups = new ConcurrentHashMap<>();
    /** Exact aliases retained only for selected/deferred projectile cleanup identities. */
    private final Map<ProjectileMembershipLedger.ProjectileIdentity, Entity> projectileAliases = new ConcurrentHashMap<>();
    private final Map<TaskCorrelationId, PendingProjectileRemoval> pendingProjectileRemovals = new ConcurrentHashMap<>();
    private final Map<ProjectileMembershipLedger.ProjectileIdentity, DeferredProjectileCleanup> deferredProjectileCleanups = new ConcurrentHashMap<>();
    private final Map<TaskCorrelationId, ChunkWorkRequest> chunkRequests = new ConcurrentHashMap<>();
    private final java.util.Set<WorldId> observedChunkFutureWorlds = ConcurrentHashMap.newKeySet();
    private final LivePacketReplayGate movementReplay = new LivePacketReplayGate();
    private final ThreadLocal<ReplayIdentity> replayIdentity = new ThreadLocal<>();
    private final java.util.Set<TaskCorrelationId> consumedInteractionReplays = ConcurrentHashMap.newKeySet();
    private final java.util.Set<TaskCorrelationId> consumedBlockPacketReplays = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean firstPlayerActionFailure = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean firstUseItemOnFailure = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean firstUseItemFailure = new java.util.concurrent.atomic.AtomicBoolean();
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
    private long playerActionIngress, playerActionOwnerAdmission, playerActionOwnerExecution, playerActionReplay,
        playerActionHandlerCompletion, playerActionStaleTicketRejected;
    private long useItemOnIngress, useItemOnOwnerAdmission, useItemOnOwnerExecution, useItemOnReplay,
        useItemOnHandlerCompletion, useItemOnStaleTicketRejected;
    private long useItemIngress, useItemOwnerAdmission, useItemOwnerExecution, useItemReplay,
        useItemHandlerCompletion, useItemStaleTicketRejected;
    private long blockPacketClosureRejected, blockPacketDuplicate;
    private long entityRelocations;
    private long pearlCleanupAdmissions;
    private long pearlCleanupExecutions;
    private long pearlCleanupRejections;
    private long projectileCleanupAdmissions;
    private long projectileCleanupExecutions;
    private long projectileCleanupRejections;
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
        var portalAuthority = new PearlPortalTransitionManager.Authority() {
            @Override public boolean running() { return PhoeniumLiveRuntime.this.isRunning(); }
            @Override public boolean worldActive(ServerLevel level) {
                if (!running() || serverInstance == null || level == null || level.getServer() != serverInstance) return false;
                for (ServerLevel current : serverInstance.getAllLevels()) if (current == level) return true;
                return false;
            }
            @Override public WorldId worldId(ServerLevel level) { return PhoeniumLiveRuntime.worldId(level); }
            @Override public void execute(RoutingKey key, RegionalServiceKind kind, Runnable body) {
                if (OwnershipContext.current().isPresent())
                    throw new IllegalStateException("portal authority stage entered with retained owner authority");
                regionalDispatcher.executeAndDrain(key, kind, ignored -> body.run());
            }
            @Override public java.util.function.BooleanSupplier prepare(Entity source,
                    net.minecraft.world.level.portal.TeleportTransition transition, RoutingKey destination) {
                if (OwnershipContext.current().isPresent()) throw new IllegalStateException("portal preparation retained owner authority");
                ServerLevel level = transition.newLevel();
                // Consume only the real public FULL input needed by this native insertion.
                // getChunk's native wait, if necessary, occurs here without any owner lease.
                var chunk = level.getChunkSource().getChunk(destination.x(), destination.z(),
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
                if (chunk == null) throw new IllegalStateException("portal destination FULL input absent");
                var inputs = (dev.phoenium.chunk.FullInputView) level.getChunkSource().chunkMap;
                long position = new net.minecraft.world.level.ChunkPos(destination.x(), destination.z()).pack();
                var holder = inputs.phoenium$visibleInput(position);
                if (holder == null) throw new IllegalStateException("portal destination visible holder absent");
                int range = level.getServer().getScaledTrackingDistance(source.getType().clientTrackingRange() * 16);
                regionalDispatcher.ensureClosure(destination, Math.max(1, (range + 15) / 16 + 2));
                return () -> worldActive(level) && inputs.phoenium$visibleInput(position) == holder
                    && level.getChunkSource().getChunkNow(destination.x(), destination.z()) == chunk;
            }
            @Override public void retireExpectedSource(Entity source, Entity.RemovalReason reason, ObjectOwnership expected) {
                if (!entityIndexLifecycle.retirePortalPublication(expected, worldId((ServerLevel) source.level()), source.getId()))
                    throw new IllegalStateException("portal retirement no longer owns its exact entity publication");
                if (source instanceof FullEntityFacts.Access access)
                    mobDecisions.retire(source.getUUID().toString(), access.phoenium$entityIncarnation());
                retirePearlMembership(source); retireProjectileMembership(source);
            }
        };
        this.pearlPortalTransitions = new PearlPortalTransitionManager(portalAuthority, objects);
        this.itemPortalTransitions = new ItemPortalTransitionManager(portalAuthority, objects);
        this.playerDimensionTransitions = new PlayerDimensionTransitionManager(
            new PlayerDimensionTransitionManager.Authority() {
                @Override public boolean running() { return PhoeniumLiveRuntime.this.isRunning(); }
                @Override public boolean worldActive(ServerLevel level) {
                    if (!running() || serverInstance == null || level == null || level.getServer() != serverInstance) return false;
                    for (ServerLevel current : serverInstance.getAllLevels()) if (current == level) return true;
                    return false;
                }
                @Override public WorldId worldId(ServerLevel level) { return PhoeniumLiveRuntime.worldId(level); }
                @Override public void execute(RoutingKey key, RegionalServiceKind kind, Runnable body) {
                    if (OwnershipContext.current().isPresent())
                        throw new IllegalStateException("player dimension stage entered with retained owner authority");
                    regionalDispatcher.executeAndDrain(key, kind, ignored -> body.run());
                }
                @Override public void retireSourceIndex(ServerPlayer player, WorldId sourceWorld,
                                                        long registryIncarnation) {
                    entityIndexLifecycle.retireTransitionSource(player.getUUID().toString(), sourceWorld,
                        player.getId(), registryIncarnation);
                }
            }, directory, objects);
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
            Class.forName(PortalProcessor.class.getName(), true, loader);
            Class.forName(Projectile.class.getName(), true, loader);
            Class.forName(net.minecraft.world.entity.projectile.ThrowableProjectile.class.getName(), true, loader);
            Class.forName(ThrownEnderpearl.class.getName(), true, loader);
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
        scheduler = new RegionScheduler(runtimes, directory, barrier, this, this,
            dev.phoenium.scheduler.GameplayWorkerConfig.fromProperties());
        gateway = new OwnerGateway(directory, runtimes, scheduler);
        objectGateway = new ObjectOwnerGateway(objects, directory, gateway, this::acknowledgeRejectedPacket);
        chunkGateway = new ChunkCompletionGateway(directory, gateway, chunkCustody);
        regionalDispatcher = new RegionalServiceDispatcher("phoenium-regional-services", lifecycleStarts + 1,
            directory, runtimes, gateway, scheduler);
        regionalServices = new RegionalWorldServices(directory, regionalDispatcher, objects);
        initializeRegionalSaves(regionalDispatcher);
        // Reload can populate the native tracker map before SERVER_STARTED. Bootstrap
        // reads its real published entity facts under the same named service turn used
        // by later tracking initialization, before any regional callback is admitted.
        try (var turn = dev.phoenium.chunk.FullFinishingServices.forServer(server)
                .serial(dev.phoenium.chunk.FullFinishingServices.Phase.SERVER_TASK)) {
            for (ServerLevel level : server.getAllLevels()) regionalServices.bind(level);
        }
        if (fullEnclosure == null)
            fullEnclosure = new dev.phoenium.chunk.FullRegionalEnclosure(directory, runtimes, gateway, objects, regionalServices);
        else fullEnclosure.installStateMigration(regionalServices);
        regionalDispatcher.installBoundaryAdvancer(fullEnclosure::advance);
        regionalDispatcher.installClosureEncloser(fullEnclosure::enclose);
        state = State.RUNNING;
        ChunkRuntimeTrace.start();
        for (ServerLevel level : server.getAllLevels()) refreshEntityBindings(level);
        // Rebuild the complete multi-world association set once, after all native worlds have
        // been observed, so deterministic restart ordering is global rather than per-world.
        schedulePearlCleanup(pearlMemberships.reconstructDeterministically());
        if (projectileMemberships.settings().enabled())
            scheduleProjectileCleanup(projectileMemberships.reconstructDeterministically());
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
                if (!pendingTick.queued) advanceConsecutiveOwnerFreePhases(
                    () -> pendingTick.queued,
                    () -> pendingTick.step == PendingTick.Step.DONE,
                    () -> scheduler.lifecycle().acceptsSimulationWork(),
                    () -> pendingTick.step,
                    () -> advanceOwnerFreeTickPhase(pendingTick, live));
                if (!scheduler.lifecycle().acceptsSimulationWork()) break;
                if (!pendingTick.queued)
                    throw new IllegalStateException("live tick coordinator completed without reachable owner work");
                advanceRequiredOwnerWork(scheduler);
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
            case FINAL_PREFIX_OWNER -> {
                pending.runEntityBody = phases.phoenium$beforeEntityTicks(pending.levelState);
                pending.step = PendingTick.Step.FINAL_DECISION_COORDINATOR;
            }
            case FINAL_OWNER -> {
                phases.phoenium$afterEntityTicks(pending.levelState);
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
                // A native entity can cross a chunk-owner boundary between live ticks (for
                // example, an ender pearl moved by the vanilla lifecycle callback). Reconcile
                // that bounded observation before the next chunk-gameplay phase reads the entity
                // from its destination chunk. The entity-management cut below remains necessary
                // for movement first observed during this tick's entity body.
                entityIndexLifecycle.advancePendingRelocations(runtimes);
                itemPortalTransitions.advance();
                pearlPortalTransitions.advance();
                playerDimensionTransitions.advance();
                retryDeferredPearlCleanups();
                if (projectileMemberships.settings().enabled()) retryDeferredProjectileCleanups();
                // Native providers can replace a settled slot during coordinator work.
                // Re-observe their original futures without replaying the distance-update prefix.
                pending.readiness = LiveTickReadiness.refresh(pending.readiness);
                phases.phoenium$afterChunkTick(pending.levelState, pending.cacheState);
                pending.step = PendingTick.Step.BLOCK_EVENTS_COORDINATOR;
            }
            case BLOCK_EVENTS_COORDINATOR -> {
                LiveTickPhases.finishCacheMaintenance(pending.levelState.cache(), pending.cacheState);
                pending.levelState.finishMaintenance(pending.level());
                phases.phoenium$runBlockEvents(pending.levelState);
                queueTickPhase(pending, live, PendingTick.Step.FINAL_PREFIX_OWNER);
            }
            case FINAL_DECISION_COORDINATOR -> {
                if (pending.runEntityBody) {
                    phases.phoenium$beforeEntityList(pending.levelState);
                    phases.phoenium$runEntityTicks(pending.levelState);
                    phases.phoenium$afterEntityList(pending.levelState);
                    pending.step = PendingTick.Step.ENTITY_MANAGEMENT_COORDINATOR;
                }
                else {
                    pending.levelState.skipEntityBody(pending.level());
                    pending.step = PendingTick.Step.ENTITY_MANAGEMENT_COORDINATOR;
                }
            }
            case ENTITY_MANAGEMENT_COORDINATOR -> {
                // Native entity-storage application can introduce entities from any current
                // chunk owner. Its registry coordinator runs before owner admission; tracking
                // callbacks and stored-entity removal each obtain their own truthful owner turn.
                try (var access = dev.phoenium.chunk.FullFinishingServices.forServer(pending.level().getServer()).referenceAccess()) {
                    phases.phoenium$runEntityManagement(pending.levelState);
                }
                // Entity movement observations made by the native body above are committed only
                // here, after every owner callback has returned. Busy source/destination regions
                // retain their bounded fact for the next tick instead of waiting on one another.
                entityIndexLifecycle.advancePendingRelocations(runtimes);
                itemPortalTransitions.advance();
                pearlPortalTransitions.advance();
                playerDimensionTransitions.advance();
                retryDeferredPearlCleanups();
                if (projectileMemberships.settings().enabled()) retryDeferredProjectileCleanups();
                queueTickPhase(pending, live, PendingTick.Step.FINAL_OWNER);
            }
            default -> throw new IllegalStateException("owner-free tick coordinator reached " + pending.step);
        }
    }

    static void advanceConsecutiveOwnerFreePhases(java.util.function.BooleanSupplier queued,
            java.util.function.BooleanSupplier complete, java.util.function.BooleanSupplier acceptsWork,
            java.util.function.Supplier<?> marker, Runnable advance) {
        int remaining = PendingTick.Step.values().length;
        while (!queued.getAsBoolean() && !complete.getAsBoolean() && acceptsWork.getAsBoolean()) {
            Object before = marker.get();
            advance.run();
            if (!queued.getAsBoolean() && !complete.getAsBoolean() && Objects.equals(before, marker.get()))
                throw new IllegalStateException("owner-free live tick coordinator made no progress at " + before);
            if (--remaining == 0 && !queued.getAsBoolean() && !complete.getAsBoolean())
                throw new IllegalStateException("owner-free live tick coordinator exceeded finite phase chain");
        }
    }

    static void advanceRequiredOwnerWork(RegionScheduler scheduler) {
        long before = scheduler.advances();
        scheduler.advanceOne();
        if (scheduler.advances() == before && scheduler.lifecycle() != SchedulerLifecycle.FAILED)
            throw new IllegalStateException("admitted live tick was not reachable through the owner scheduler");
    }

    public void runRegionalChunkGameplay(ServerChunkCache cache) {
        requireRegionalServiceLifetime();
        if (regionalServices == null) throw new IllegalStateException("regional chunk gameplay before service binding");
        regionalServices.runChunkGameplay(cache);
    }

    public net.minecraft.world.level.NaturalSpawner.SpawnState regionalSpawnState(ServerChunkCache cache, int count,
            Iterable<net.minecraft.world.entity.Entity> entities, net.minecraft.world.level.NaturalSpawner.ChunkGetter chunks,
            dev.phoenium.service.RegionalChunkGameplayService.SpawnFactory factory) {
        requireRegionalServiceLifetime(); return regionalServices.createSpawnState(cache, count, entities, chunks, factory);
    }
    public void regionalCollectSpawningChunks(ServerChunkCache cache, net.minecraft.server.level.ChunkMap map,
            java.util.List<net.minecraft.world.level.chunk.LevelChunk> output) {
        requireRegionalServiceLifetime(); regionalServices.collectSpawningChunks(cache, map, output);
    }
    public void regionalSpawningChunkCallback(ServerChunkCache cache, net.minecraft.world.level.chunk.LevelChunk chunk,
            boolean canSpawn, Runnable original) {
        requireRegionalServiceLifetime(); regionalServices.spawningChunkCallback(cache, chunk, canSpawn, original);
    }
    public void regionalBroadcastChunkCallback(ServerChunkCache cache, net.minecraft.world.level.chunk.LevelChunk chunk, Runnable original) {
        requireRegionalServiceLifetime(); regionalServices.broadcastChunkCallback(cache, chunk, original);
    }
    public void regionalCustomSpawnerCallback(ServerLevel level, net.minecraft.world.level.CustomSpawner spawner,
            boolean spawnEnemies, Runnable original) {
        requireRegionalServiceLifetime(); regionalServices.customSpawnerCallback(level, spawner, spawnEnemies, original);
    }
    public void regionalForEachBlockTickingChunk(ServerChunkCache cache, net.minecraft.server.level.ChunkMap map,
            java.util.function.Consumer<net.minecraft.world.level.chunk.LevelChunk> output) {
        requireRegionalServiceLifetime(); regionalServices.forEachBlockTickingChunk(cache, map, output);
    }
    public void runRegionalEntityTicks(ServerLevel level, net.minecraft.world.level.entity.EntityTickList entities,
            java.util.function.Consumer<Entity> output) {
        requireRegionalServiceLifetime(); regionalServices.runEntityTicks(level, entities, output);
    }
    public void runRegionalBlockEntities(ServerLevel level) {
        requireRegionalServiceLifetime(); regionalServices.runBlockEntities(level);
    }
    public void runRegionalDragonFight(ServerLevel level,
            net.minecraft.world.level.dimension.end.EnderDragonFight fight) {
        requireRegionalServiceLifetime(); regionalServices.runDragonFight(level, fight);
    }
    public void onBlockEntityTickerAdded(net.minecraft.world.level.Level level,
            net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {
        requireRegionalServiceLifetime();
        if (regionalServices != null && level instanceof ServerLevel serverLevel)
            regionalServices.blockEntityTickerAdded(serverLevel, ticker);
    }
    public net.minecraft.world.entity.player.Player regionalNaturalSpawnPlayer(ServerLevel level, double x, double y, double z) {
        requireRegionalServiceLifetime(); return regionalServices.nearestNaturalSpawnPlayer(level, x, y, z);
    }
    public double regionalNaturalSpawnDistance(ServerLevel level, net.minecraft.world.entity.player.Player player,
            double x, double y, double z) {
        requireRegionalServiceLifetime(); return regionalServices.naturalSpawnPlayerDistance(level, player, x, y, z);
    }
    public boolean regionalNaturalSpawnActive(ServerLevel level) {
        requireRegionalServiceLifetime(); return regionalServices != null && regionalServices.activeNaturalSpawn(level);
    }
    public boolean regionalSpawningWeather(ServerLevel level, boolean thunder) {
        requireRegionalServiceLifetime(); return regionalServices.retainedSpawningWeather(level, thunder);
    }
    public boolean regionalSpawningWeatherActive(ServerLevel level) {
        requireRegionalServiceLifetime(); return regionalServices != null && regionalServices.activeSpawningWeather(level);
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
            failures.run(playerDimensionTransitions::close);
            failures.run(pearlPortalTransitions::close);
            failures.run(itemPortalTransitions::close);
            failures.run(() -> { if (scheduler != null) { scheduler.quiesce(); scheduler.stop(); } });
            failures.run(() -> {
                int abandoned = regionalDispatcher == null ? 0 : regionalDispatcher.abandonPending();
                int packetCount = packets.size(); packets.clear();
                invalidatedMovementPackets.clear();
                movementTransitionFences.clear();
                movementTransitionEpochs.clear();
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
            failures.run(entityIndexLifecycle::close);
            failures.run(() -> {
                pendingPearlRemovals.clear();
                deferredPearlCleanups.clear();
                pearlAliases.clear();
                pearlMemberships.close();
                pendingProjectileRemovals.clear();
                deferredProjectileCleanups.clear();
                projectileAliases.clear();
                projectileMemberships.close();
                mobDecisions.close();
            });
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
        return new LiveDiagnostics(state, scheduler == null ? null : scheduler.lifecycle(), lifecycleStarts, lifecycleStops,
            scheduler == null ? 1 : scheduler.workerCount(), scheduler != null && scheduler.workerCount() > 1,
            regions.size(), activeLeases, intercepts, dispatches, executions, duplicates, unexpectedBypasses, fallbacks,
            ownershipViolations, unleasedExecutions, hardBarriers, barrier.tripped(), objects.liveCount(), objects.playerCount(),
            entityIndexLifecycle.pendingRelocationCount(), entityRelocations,
            pearlMemberships.membershipCount(), pendingPearlRemovals.size() + deferredPearlCleanups.size(),
            pearlMemberships.settings().enabled(), pearlMemberships.settings().cap(),
            pearlCleanupAdmissions, pearlCleanupExecutions, pearlCleanupRejections,
            projectileMemberships.membershipCount(), pendingProjectileRemovals.size() + deferredProjectileCleanups.size(),
            projectileMemberships.settings().enabled(), projectileMemberships.settings().cap(),
            projectileCleanupAdmissions, projectileCleanupExecutions, projectileCleanupRejections,
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
            interactionStaleTicketRejected, playerActionIngress, playerActionOwnerAdmission, playerActionOwnerExecution,
            playerActionReplay, playerActionHandlerCompletion, playerActionStaleTicketRejected, useItemOnIngress,
            useItemOnOwnerAdmission, useItemOnOwnerExecution, useItemOnReplay, useItemOnHandlerCompletion,
            useItemOnStaleTicketRejected, useItemIngress, useItemOwnerAdmission, useItemOwnerExecution,
            useItemReplay, useItemHandlerCompletion, useItemStaleTicketRejected,
            blockPacketClosureRejected, blockPacketDuplicate);
    }
    public String failureDescription() {
        return barrier.record().map(record -> record.cause().toString()).orElse("");
    }

    public ChunkBindingRuntime chunkBinding() { return chunkBinding; }
    public RegionalWorldServices regionalServices() {
        if (regionalServices == null) throw new IllegalStateException("regional services unavailable before live start");
        return regionalServices;
    }
    /** Called before original producer mutations, including container registration.
     * Only an as-yet-unbound world may use its original bootstrap path. */
    public void requireRegionalServiceLifetime() {
        if (regionalServices != null) regionalServices.requireActiveLifetime();
    }
    private boolean regionalServiceBound() {
        requireRegionalServiceLifetime();
        return regionalServices != null;
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
        if (regionalServiceBound()) regionalServices.containerAdded(owner, pos, container);
    }
    public <T> void regionalTickContainerRemoved(LevelTicks<T> owner, ChunkPos pos, LevelChunkTicks<T> container) {
        if (regionalServiceBound()) regionalServices.containerRemoved(owner, pos, container);
    }
    public <T> void regionalTickScheduled(LevelTicks<T> owner, long chunkKey, LevelChunkTicks<T> container) {
        if (regionalServiceBound()) regionalServices.scheduled(owner, chunkKey, container);
    }
    public <T> boolean scheduleRegionalTick(LevelTicks<T> owner, net.minecraft.world.ticks.ScheduledTick<T> tick) {
        return regionalServiceBound() && regionalServices.schedule(owner, tick);
    }
    public <T> boolean mutateRegionalTickArea(LevelTicks<T> owner, LevelTicks<T> source,
            net.minecraft.world.level.levelgen.structure.BoundingBox area, net.minecraft.core.Vec3i offset, Runnable original) {
        return regionalServiceBound() && regionalServices.mutateTickArea(owner, source, area, offset, original);
    }
    public <T> Boolean regionalWillTickThisTick(LevelTicks<T> owner, BlockPos pos, T type) {
        return !regionalServiceBound() ? null : regionalServices.willTickThisTick(owner, pos, type);
    }
    public <T> boolean runRegionalScheduledTicks(LevelTicks<T> owner, long currentTick, int maxTicks, BiConsumer<BlockPos, T> output) {
        return regionalServiceBound() && regionalServices.runScheduled(owner, currentTick, maxTicks, output);
    }
    public boolean enqueueRegionalBlockEvent(ServerLevel level, BlockEventData event) {
        return regionalServiceBound() && regionalServices.enqueueBlockEvent(level, event);
    }
    public boolean runRegionalBlockEvents(ServerLevel level) {
        return regionalServiceBound() && regionalServices.runBlockEvents(level);
    }
    public boolean runRegionalTracking(net.minecraft.server.level.ChunkMap map) {
        return regionalServiceBound() && regionalServices.runTracking(map);
    }
    public boolean regionalTrackingAdd(net.minecraft.server.level.ChunkMap map, Entity entity, Runnable original) {
        return regionalServiceBound() && regionalServices.addTrackingEntity(map, entity, original);
    }

    /**
     * C2ME's async final stage is waiting on its captured authoritative POI queue to run through
     * a typed regional service and release that lease before the provider continuation resumes.
     * Main-thread managedBlock/pollTask must therefore be able to advance the existing owner
     * scheduler, but only while that exact service kind is pending. FIFO and trySelect/promote
     * remain unchanged.
     */
    public boolean pumpC2mePersistedReadOne() {
        if (OwnershipContext.current().isPresent() || dev.phoenium.chunk.FullFinishingServices.finishing()
                || regionalDispatcher == null || scheduler == null
                || !regionalDispatcher.hasPending(RegionalServiceKind.C2ME_PERSISTED_POI_RECONCILE)) return false;
        long before = scheduler.advances();
        scheduler.advanceOne();
        return scheduler.advances() != before;
    }
    public dev.phoenium.scheduler.GameplayMeasurements.Snapshot gameplayMeasurements() {
        return scheduler == null ? null : scheduler.measurements().snapshot();
    }
    public Boolean regionalEntityStorageSave(ServerLevel level, long chunk,
            java.util.function.Supplier<java.util.List<Entity>> selection, java.util.function.BooleanSupplier original) {
        if (!regionalServiceBound()) return null;
        return regionalServices.storeEntitySections(level, chunk, selection, original);
    }
    public boolean regionalTrackingAddPlayers(net.minecraft.server.level.ChunkMap map, Object tracker,
                                              java.util.List<ServerPlayer> players) {
        return regionalServiceBound() && regionalServices.addTrackingPlayers(map, tracker, players);
    }
    public boolean regionalTrackingAddPlayer(net.minecraft.server.level.ChunkMap map, Object tracker,
                                             ServerPlayer player, Runnable original) {
        return regionalServiceBound() && regionalServices.addTrackingPlayer(map, tracker, player, original);
    }
    public boolean regionalTrackingRemove(net.minecraft.server.level.ChunkMap map, Entity entity, Runnable original) {
        return regionalServiceBound() && regionalServices.removeTrackingEntity(map, entity, original);
    }
    public boolean regionalTrackingRemovePlayer(net.minecraft.server.level.ChunkMap map, Object tracker,
                                                ServerPlayer player, Runnable original) {
        return regionalServiceBound() && regionalServices.removeTrackingPlayer(map, tracker, player, original);
    }
    public boolean regionalTrackingMove(net.minecraft.server.level.ChunkMap map, ServerPlayer player, Runnable original) {
        return regionalServiceBound() && regionalServices.moveTrackingPlayer(map, player, original);
    }
    public boolean regionalTrackingMovedPlayers(net.minecraft.server.level.ChunkMap map, Object tracker,
                                                java.util.List<ServerPlayer> players) {
        return regionalServiceBound() && regionalServices.movedTrackingPlayers(map, tracker, players);
    }
    public boolean regionalTrackingMovedPlayer(net.minecraft.server.level.ChunkMap map, Object tracker,
                                               ServerPlayer player, Runnable original) {
        return regionalServiceBound() && regionalServices.movedTrackingPlayer(map, tracker, player, original);
    }
    public void regionalTrackingSourceAdded(net.minecraft.server.level.ChunkMap map, Entity entity) {
        if (regionalServiceBound()) regionalServices.trackingSourceAdded(map, entity);
    }

    public void regionalTrackingSourceRemoved(net.minecraft.server.level.ChunkMap map, Entity entity) {
        if (regionalServiceBound()) regionalServices.trackingSourceRemoved(map, entity);
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
        // ServerLevel's constructor return publishes the complete world on the coordinator,
        // before native ticket activation can start provider reads. Producers may only consume
        // that publication; they must never compete to build directory generations or services.
        if (!level.getServer().isSameThread() || OwnershipContext.current().isPresent())
            throw new IllegalStateException("chunk world requested before coordinator publication " + world.value());

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
        runtimes.put(region, runtime);
        chunkPrerequisiteServices.put(world,
                new ChunkPrerequisiteServices(world, "live-" + worldInstanceSequence, worldInstanceSequence));
        directory.publish(generation, routes, defaults);
        // Release publication is last: a producer observing this marker sees all prior services
        // and routes. No new global lock, provider wait, or owner-to-owner wait is required.
        if (regions.putIfAbsent(world, new LiveRegion(level, region, key, runtime, worldInstanceSequence)) != null)
            throw new IllegalStateException("duplicate controlled world " + world.value());
        return chunkAuthority(world);
    }

    private ChunkBindingRuntime.WorldAuthority chunkAuthority(WorldId world) {
        LiveRegion region = Objects.requireNonNull(regions.get(world));
        return new ChunkBindingRuntime.WorldAuthority(world, region.worldInstanceSequence(),
                Objects.requireNonNull(chunkPrerequisiteServices.get(world)), directory, id -> {
                    // Registration and topology publication happen on the coordinator. Native
                    // admission resolves the published route before consulting its owner here.
                    RegionRuntime runtime = id.world().equals(world) ? runtimes.get(id) : null;
                    return runtime == null ? null : runtime.leases();
                }, barrier);
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
    public boolean matchesPlayerActionReplay(ServerGamePacketListenerImpl connection, ServerboundPlayerActionPacket packet) {
        ReplayIdentity active = replayIdentity.get();
        boolean match = active != null && active.kind == ReplayKind.PLAYER_ACTION
            && active.connection == connection && active.packet == packet;
        if (match) playerActionReplay++;
        return match;
    }
    public boolean matchesUseItemOnReplay(ServerGamePacketListenerImpl connection, ServerboundUseItemOnPacket packet) {
        ReplayIdentity active = replayIdentity.get();
        boolean match = active != null && active.kind == ReplayKind.USE_ITEM_ON
            && active.connection == connection && active.packet == packet;
        if (match) useItemOnReplay++;
        return match;
    }
    public boolean matchesUseItemReplay(ServerGamePacketListenerImpl connection, ServerboundUseItemPacket packet) {
        ReplayIdentity active = replayIdentity.get();
        boolean match = active != null && active.kind == ReplayKind.USE_ITEM
            && active.connection == connection && active.packet == packet;
        if (match) useItemReplay++;
        return match;
    }

    /** Called at the required valid-input seam, before resetPosition, jumping, or player movement. */
    public void admitMovePacket(ServerGamePacketListenerImpl connection, ServerboundMovePlayerPacket packet,
                                double lastGoodX, double lastGoodY, double lastGoodZ, boolean awaitingTeleport) {
        movementIngress++;
        if (!acceptsGameplay()) { packetRejections++; return; }
        ServerPlayer player = connection.player;
        if (player == null) { packetRejections++; movementFootprintRejected++; return; }
        String objectId = player.getUUID().toString();
            WorldId actualWorld = worldId(player.level());
            MovementTransitionFence transition = movementTransitionFences.get(objectId);
            if (transition != null) {
                double requestedX = packet.getX(player.getX());
                double requestedY = packet.getY(player.getY());
                double requestedZ = packet.getZ(player.getZ());
                if (!transition.destinationWorld().equals(actualWorld)
                        || awaitingTeleport
                        || !transition.near(player.getX(), player.getY(), player.getZ(),
                        requestedX, requestedY, requestedZ)) {
                    movementBootstrapDeferred++;
                    return;
                }
            }
            ObjectOwnership current = bindPlayer(player);
            // The native level edge publishes the destination observation before the owner-free
            // relocation cut can commit it. Keep the transition fence closed until the binding itself
            // is destination-world authoritative; otherwise the first apparently rebased packet could
            // still be selected through the old owner while vanilla performs destination collision
            // lookup inside that owner transaction.
            if (transition != null && !current.owner().world().equals(actualWorld)) {
                movementBootstrapDeferred++;
                return;
            }
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
                if (regionalServiceBound()) {
                    var footprint = regionalServices.prepareMovementTracking(player, snapshot.footprint());
                    // Enclosure may publish a legitimate merge and drain earlier accepted work.
                    // Recheck the original movement baseline before granting this packet a ticket.
                    var after = LiveMovementSnapshot.capture(player, packet, lastGoodX, lastGoodY, lastGoodZ, awaitingTeleport);
                    if (!after.classified() || !after.footprint().equals(snapshot.footprint())) {
                        movementBootstrapDeferred++;
                        return;
                    }
                    current = bindPlayer(player);
                    ticket = LiveMovementAdmission.admit(directory, current.owner(), footprint);
                }
                movementFootprintAccepted++;
                long transitionEpoch = movementTransitionEpochs.getOrDefault(objectId, 0L);
                PacketAdmissionResult admission = admitPacket(connection, player, new PendingMove(this, connection, packet, player.level(), current.objectId(),
                        current.incarnation(), transitionEpoch, ticket),
                    "player-movement", current, AdmissionPolicy.REJECT);
                if (admission.admitted() && transition != null) movementTransitionFences.remove(objectId, transition);
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

    /** Admits block breaking/action packets through the player's current regional owner. */
    public void admitPlayerActionPacket(ServerGamePacketListenerImpl connection, ServerboundPlayerActionPacket packet) {
        playerActionIngress++;
        if (!acceptsGameplay()) {
            packetRejections++;
            reportPlayerActionFailure("BEFORE_OWNER_ADMISSION", connection.player, null, packet,
                new PacketAdmissionStateException("gameplay admission is closed"));
            return;
        }
        ServerPlayer player = connection.player;
        if (player == null) {
            packetRejections++;
            reportPlayerActionFailure("BEFORE_OWNER_ADMISSION", null, null, packet,
                new PacketAdmissionStateException("connection has no player"));
            return;
        }
        try {
            BlockPacketAdmission admission = prepareBlockPacketAdmission(player, packet.getPos());
            PacketAdmissionResult result = admitPacket(connection, player,
                new PendingPlayerAction(connection, packet, player.level(), admission.binding().objectId(),
                    admission.binding().incarnation(), admission.transitionEpoch(), admission.ticket()),
                PLAYER_ACTION_OPERATION, admission.binding(), AdmissionPolicy.REJECT);
            if (result.admitted()) playerActionOwnerAdmission++;
            else reportPlayerActionFailure("DURING_OWNER_ADMISSION", player, admission, packet,
                new PacketDeliveryRejectedException(result.delivery()));
        } catch (RuntimeException rejected) {
            packetRejections++;
            blockPacketClosureRejected++;
            reportPlayerActionFailure("BEFORE_OWNER_ADMISSION", player, null, packet, rejected);
        }
    }

    /** Admits block use/placement packets through the player's current regional owner. */
    public void admitUseItemOnPacket(ServerGamePacketListenerImpl connection, ServerboundUseItemOnPacket packet) {
        useItemOnIngress++;
        if (!acceptsGameplay()) {
            packetRejections++;
            reportUseItemOnFailure("BEFORE_OWNER_ADMISSION", connection.player, null, packet,
                new PacketAdmissionStateException("gameplay admission is closed"));
            return;
        }
        ServerPlayer player = connection.player;
        if (player == null) {
            packetRejections++;
            reportUseItemOnFailure("BEFORE_OWNER_ADMISSION", null, null, packet,
                new PacketAdmissionStateException("connection has no player"));
            return;
        }
        try {
            BlockPacketAdmission admission = prepareBlockPacketAdmission(player, packet.getHitResult().getBlockPos());
            PacketAdmissionResult result = admitPacket(connection, player,
                new PendingUseItemOn(connection, packet, player.level(), admission.binding().objectId(),
                    admission.binding().incarnation(), admission.transitionEpoch(), admission.ticket()),
                USE_ITEM_ON_OPERATION, admission.binding(), AdmissionPolicy.REJECT);
            if (result.admitted()) useItemOnOwnerAdmission++;
            else reportUseItemOnFailure("DURING_OWNER_ADMISSION", player, admission, packet,
                new PacketDeliveryRejectedException(result.delivery()));
        } catch (RuntimeException rejected) {
            packetRejections++;
            blockPacketClosureRejected++;
            reportUseItemOnFailure("BEFORE_OWNER_ADMISSION", player, null, packet, rejected);
        }
    }

    /** Admits target-free item use through the current player owner; native code derives its target once on replay. */
    public void admitUseItemPacket(ServerGamePacketListenerImpl connection, ServerboundUseItemPacket packet) {
        useItemIngress++;
        if (!acceptsGameplay()) {
            packetRejections++;
            reportUseItemFailure("BEFORE_OWNER_ADMISSION", connection.player, null, packet,
                new PacketAdmissionStateException("gameplay admission is closed"));
            return;
        }
        ServerPlayer player = connection.player;
        if (player == null) {
            packetRejections++;
            reportUseItemFailure("BEFORE_OWNER_ADMISSION", null, null, packet,
                new PacketAdmissionStateException("connection has no player"));
            return;
        }
        try {
            PlayerPacketAdmission admission = preparePlayerPacketAdmission(player);
            PacketAdmissionResult result = admitPacket(connection, player,
                new PendingUseItem(connection, packet, player.level(), admission.binding().objectId(),
                    admission.binding().incarnation(), admission.transitionEpoch(), admission.ticket()),
                USE_ITEM_OPERATION, admission.binding(), AdmissionPolicy.REJECT);
            if (result.admitted()) useItemOwnerAdmission++;
            else reportUseItemFailure("DURING_OWNER_ADMISSION", player, admission.binding(), packet,
                new PacketDeliveryRejectedException(result.delivery()));
        } catch (RuntimeException rejected) {
            packetRejections++;
            reportUseItemFailure("BEFORE_OWNER_ADMISSION", player, null, packet, rejected);
        }
    }

    private PlayerPacketAdmission preparePlayerPacketAdmission(ServerPlayer player) {
        String objectId = player.getUUID().toString();
        if (movementTransitionFences.containsKey(objectId) || playerDimensionTransitionPending(player))
            throw new IllegalStateException("item-use packet arrived during player dimension transition");
        ObjectOwnership binding;
        // Packet processing precedes tickServer. Identity lookup needs its existing owner-free
        // service turn, while native replay remains queued for the server-tick TICK_LIST turn.
        try (var turn = dev.phoenium.chunk.FullFinishingServices.forServer(player.level().getServer()).referenceAccess()) {
            binding = bindPlayer(player);
        }
        LivePlayerPacketAdmission.Ticket ticket = LivePlayerPacketAdmission.admit(directory, binding);
        return new PlayerPacketAdmission(binding, movementTransitionEpochs.getOrDefault(objectId, 0L), ticket);
    }

    private BlockPacketAdmission prepareBlockPacketAdmission(ServerPlayer player, BlockPos target) {
        Objects.requireNonNull(target, "block packet target");
        String objectId = player.getUUID().toString();
        if (movementTransitionFences.containsKey(objectId) || playerDimensionTransitionPending(player))
            throw new IllegalStateException("block packet arrived during player dimension transition");
        WorldId world = worldId(player.level());
        RoutingKey playerKey = new RoutingKey(world, player.chunkPosition().x(), player.chunkPosition().z());
        RoutingKey targetKey = new RoutingKey(world, target.getX() >> 4, target.getZ() >> 4);
        Set<RoutingKey> footprint = blockPacketFootprint(playerKey, targetKey);
        // Packet processing precedes tickServer. Use the same owner-free serial boundary as the
        // existing movement/tracking admission while a closure publication drains prior work.
        ObjectOwnership binding;
        try (var turn = dev.phoenium.chunk.FullFinishingServices.forServer(player.level().getServer()).referenceAccess()) {
            if (regionalServiceBound()) regionalDispatcher.ensureClosure(footprint);
            binding = bindPlayer(player);
        }
        LiveBlockPacketAdmission.Ticket ticket = LiveBlockPacketAdmission.admit(directory, binding, footprint);
        long transitionEpoch = movementTransitionEpochs.getOrDefault(objectId, 0L);
        return new BlockPacketAdmission(binding, transitionEpoch, ticket);
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

    private PacketAdmissionResult admitPacket(ServerGamePacketListenerImpl connection, ServerPlayer player, PendingPacket packet,
                                              String operationKind, ObjectOwnership prepared, AdmissionPolicy policy) {
        if (!acceptsGameplay() || player == null) {
            packetRejections++;
            return new PacketAdmissionResult(false, null);
        }
        ObjectOwnership binding = prepared == null ? bindPlayer(player) : prepared;
        TaskCorrelationId correlation = new TaskCorrelationId(++sequence);
        packets.put(correlation, packet);
        ObjectOwnerGateway.Delivery delivery = objectGateway.submit(new OwnedObjectEnvelope(binding.objectId(), binding.objectEpoch(), operationKind,
            "minecraft-packet", correlation, new TaskSequence(sequence), policy));
        if (delivery == ObjectOwnerGateway.Delivery.QUEUED || delivery == ObjectOwnerGateway.Delivery.REROUTED) {
            packetAdmissions++;
            if (delivery == ObjectOwnerGateway.Delivery.REROUTED) packetStaleReroutes++;
            return new PacketAdmissionResult(true, delivery);
        } else {
            packets.remove(correlation);
            packetRejections++;
            return new PacketAdmissionResult(false, delivery);
        }
    }

    private void acknowledgeRejectedPacket(TaskPayload.OwnedObjectOperation operation) {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("rejected packet acknowledgement retained gameplay authority");
        if (ProjectileMembershipLedger.DISCARD_OPERATION.equals(operation.operationKind())) {
            PendingProjectileRemoval pending = pendingProjectileRemovals.remove(operation.correlationId());
            if (pending == null) throw new IllegalStateException("missing rejected projectile cleanup " + operation.correlationId());
            projectileMemberships.settleCleanup(pending.membership());
            projectileAliases.remove(pending.membership().identity(), pending.entity());
            projectileCleanupRejections++;
            return;
        }
        if (PearlMembershipLedger.DISCARD_OPERATION.equals(operation.operationKind())) {
            PendingPearlRemoval pending = pendingPearlRemovals.remove(operation.correlationId());
            if (pending == null) throw new IllegalStateException("missing rejected pearl cleanup " + operation.correlationId());
            pearlMemberships.settleCleanup(pending.membership());
            pearlAliases.remove(pending.membership().identity(), pending.entity());
            pearlCleanupRejections++;
            return;
        }
        PendingPacket rejected = packets.remove(operation.correlationId());
        if (rejected == null)
            throw new IllegalStateException("missing rejected packet custody " + operation.correlationId());
        var failure = new PacketDeliveryRejectedException(null);
        if (rejected instanceof PendingPlayerAction action)
            reportPlayerActionFailure("DURING_OWNER_ADMISSION", action.connection().player, null, action.packet(), failure);
        else if (rejected instanceof PendingUseItemOn useItemOn)
            reportUseItemOnFailure("DURING_OWNER_ADMISSION", useItemOn.connection().player, null, useItemOn.packet(), failure);
        else if (rejected instanceof PendingUseItem useItem)
            reportUseItemFailure("DURING_OWNER_ADMISSION", useItem.connection().player, null, useItem.packet(), failure);
        packetRejections++;
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
        if (ProjectileMembershipLedger.DISCARD_OPERATION.equals(operation.operationKind())) {
            executeProjectileDiscard(runtime, operation);
            return;
        }
        if (PearlMembershipLedger.DISCARD_OPERATION.equals(operation.operationKind())) {
            executePearlDiscard(runtime, operation);
            return;
        }
        ObjectOwnership executingPlayer = objects.requireCurrentOwner(operation.objectId(), operation.expectedObjectEpoch(), runtime.id());
        PendingPacket packet = packets.remove(operation.correlationId());
        if (packet == null) {
            if (isBlockPacketOperation(operation.operationKind())
                    && consumedBlockPacketReplays.contains(operation.correlationId())) {
                blockPacketDuplicate++;
                return;
            }
            throw new IllegalStateException("missing admitted packet " + operation.correlationId());
        }
        if (isBlockPacketOperation(operation.operationKind())
                && !consumedBlockPacketReplays.add(operation.correlationId())) {
            blockPacketDuplicate++;
            return;
        }
        if (packet instanceof PendingMove move) {
            movementOwnerExecution++;
            if (invalidatedMovementPackets.remove(operation.correlationId())) {
                movementStaleTicketRejected++;
                return;
            }
            try {
                if (executingPlayer.incarnation() != move.playerIncarnation()) throw new IllegalStateException("stale movement player incarnation");
                if (movementTransitionEpochs.getOrDefault(move.objectId(), 0L) != move.transitionEpoch())
                    throw new IllegalStateException("stale movement transition epoch");
                // A server-side teleport can change the live ServerPlayer level while an older
                // movement packet is still queued. Replaying that packet through vanilla would
                // make the old-world collision lookup run against the new world's owner
                // transaction, which can synchronously wait on a foreign chunk dependency.
                // Dimension identity is part of the movement admission, so that packet is
                // safely retired as stale before vanilla is invoked.
                if (move.playerLevel() != move.connection().player.level()
                        || !runtime.id().world().equals(worldId(move.connection().player.level())))
                    throw new IllegalStateException("stale movement player world");
                move.ticket().requireCurrent(directory, runtime.id());
            } catch (RuntimeException stale) { movementStaleTicketRejected++; return; }
            try (LivePacketReplayGate.Scope gate = movementReplay.enter(move.ticket(), directory, runtime.id());
                 PacketExecutionScope ignored = enterPacketExecution(operation.correlationId(), ReplayKind.MOVE, move.connection(), move.packet())) {
                try {
                    move.execute();
                    packetExecutions++;
                    movementHandlerCompletion++;
                } catch (ChunkProtocolException stale) {
                    // The native movement handler can discover a synchronous chunk dependency
                    // after admission has already passed, when a dimension edge or a concurrent
                    // owner cut races the replay. This packet is no longer safe to replay: the
                    // protocol guard must not escape the owner transaction and trip the server.
                    movementStaleTicketRejected++;
                    logger.debug("PHOENIUM_STAGE2 dropped stale movement replay correlation={} reason={}",
                            operation.correlationId(), stale.getMessage());
                }
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
        if (packet instanceof PendingPlayerAction action) {
            playerActionOwnerExecution++;
            try {
                requireCurrentBlockPacket(action, executingPlayer, runtime);
            } catch (RuntimeException stale) {
                playerActionStaleTicketRejected++;
                reportPlayerActionFailure("BEFORE_NATIVE_REPLAY", action.connection().player,
                    new BlockPacketAdmission(executingPlayer, action.transitionEpoch(), action.ticket()),
                    action.packet(), stale);
                return;
            }
            try (PacketExecutionScope ignored = enterPacketExecution(operation.correlationId(), ReplayKind.PLAYER_ACTION,
                    action.connection(), action.packet())) {
                try {
                    action.execute();
                    packetExecutions++;
                    playerActionHandlerCompletion++;
                } catch (ChunkProtocolException stale) {
                    playerActionStaleTicketRejected++;
                    reportPlayerActionFailure(packetFailureStage(stale), action.connection().player,
                        new BlockPacketAdmission(executingPlayer, action.transitionEpoch(), action.ticket()),
                        action.packet(), stale);
                }
            }
            return;
        }
        if (packet instanceof PendingUseItemOn useItemOn) {
            useItemOnOwnerExecution++;
            try {
                requireCurrentBlockPacket(useItemOn, executingPlayer, runtime);
            } catch (RuntimeException stale) {
                useItemOnStaleTicketRejected++;
                reportUseItemOnFailure("BEFORE_NATIVE_REPLAY", useItemOn.connection().player,
                    new BlockPacketAdmission(executingPlayer, useItemOn.transitionEpoch(), useItemOn.ticket()),
                    useItemOn.packet(), stale);
                return;
            }
            try (PacketExecutionScope ignored = enterPacketExecution(operation.correlationId(), ReplayKind.USE_ITEM_ON,
                    useItemOn.connection(), useItemOn.packet())) {
                try {
                    useItemOn.execute();
                    packetExecutions++;
                    useItemOnHandlerCompletion++;
                } catch (ChunkProtocolException stale) {
                    useItemOnStaleTicketRejected++;
                    reportUseItemOnFailure(packetFailureStage(stale), useItemOn.connection().player,
                        new BlockPacketAdmission(executingPlayer, useItemOn.transitionEpoch(), useItemOn.ticket()),
                        useItemOn.packet(), stale);
                }
            }
            return;
        }
        if (packet instanceof PendingUseItem useItem) {
            useItemOwnerExecution++;
            try {
                requireCurrentUseItemPacket(useItem, executingPlayer, runtime);
            } catch (RuntimeException stale) {
                useItemStaleTicketRejected++;
                reportUseItemFailure("BEFORE_NATIVE_REPLAY", useItem.connection().player,
                    executingPlayer, useItem.packet(), stale);
                return;
            }
            try (PacketExecutionScope ignored = enterPacketExecution(operation.correlationId(), ReplayKind.USE_ITEM,
                    useItem.connection(), useItem.packet())) {
                try {
                    useItem.execute();
                    packetExecutions++;
                    useItemHandlerCompletion++;
                } catch (ChunkProtocolException stale) {
                    useItemStaleTicketRejected++;
                    reportUseItemFailure(packetFailureStage(stale), useItem.connection().player,
                        executingPlayer, useItem.packet(), stale);
                }
            }
            return;
        }
        throw new IllegalStateException("unknown admitted packet");
    }

    private void requireCurrentBlockPacket(PendingBlockPacket packet, ObjectOwnership executingPlayer,
                                           RegionRuntime runtime) {
        if (executingPlayer.incarnation() != packet.playerIncarnation())
            throw new IllegalStateException("stale block packet player incarnation");
        if (movementTransitionEpochs.getOrDefault(packet.objectId(), 0L) != packet.transitionEpoch())
            throw new IllegalStateException("stale block packet transition epoch");
        if (packet.playerLevel() != packet.connection().player.level()
                || !runtime.id().world().equals(worldId(packet.connection().player.level())))
            throw new IllegalStateException("stale block packet player world");
        packet.ticket().requireCurrent(directory, objects, runtime.id());
    }

    private void requireCurrentUseItemPacket(PendingUseItem packet, ObjectOwnership executingPlayer,
                                             RegionRuntime runtime) {
        if (executingPlayer.incarnation() != packet.playerIncarnation())
            throw new IllegalStateException("stale item-use player incarnation");
        if (movementTransitionEpochs.getOrDefault(packet.objectId(), 0L) != packet.transitionEpoch())
            throw new IllegalStateException("stale item-use transition epoch");
        if (packet.playerLevel() != packet.connection().player.level()
                || !runtime.id().world().equals(worldId(packet.connection().player.level())))
            throw new IllegalStateException("stale item-use player world");
        packet.ticket().requireCurrent(directory, objects, runtime.id());
    }

    private static String packetFailureStage(ChunkProtocolException failure) {
        String message = failure.getMessage();
        return message != null && (message.contains("TICK_LIST") || message.contains("world service access"))
            ? "DURING_WORLD_SERVICE_TICK_LIST_ACCESS" : "DURING_NATIVE_REPLAY";
    }

    private void reportPlayerActionFailure(String stage, ServerPlayer player, BlockPacketAdmission admission,
                                           ServerboundPlayerActionPacket packet, RuntimeException failure) {
        Set<RoutingKey> footprint = admission == null
            ? packetDiagnosticValue(failure, () -> requestedBlockFootprint(player, packet.getPos()), Set.of())
            : admission.ticket().footprint();
        ObjectOwnership binding = admission == null
            ? packetDiagnosticValue(failure, () -> currentPlayerBinding(player), null) : admission.binding();
        String facts = packetDiagnosticValue(failure,
            () -> "action=" + packet.getAction() + " target=" + packet.getPos(), "UNAVAILABLE");
        reportFirstPacketFailure(firstPlayerActionFailure, "ServerboundPlayerActionPacket", stage, player,
            binding, footprint, admission == null ? null : admission.ticket().generation(), failure,
            facts);
    }

    private void reportUseItemOnFailure(String stage, ServerPlayer player, BlockPacketAdmission admission,
                                        ServerboundUseItemOnPacket packet, RuntimeException failure) {
        BlockPos clicked = packetDiagnosticValue(failure, () -> packet.getHitResult().getBlockPos(), null);
        Set<RoutingKey> footprint = admission == null
            ? packetDiagnosticValue(failure, () -> requestedBlockFootprint(player, clicked), Set.of())
            : admission.ticket().footprint();
        ObjectOwnership binding = admission == null
            ? packetDiagnosticValue(failure, () -> currentPlayerBinding(player), null) : admission.binding();
        String facts = packetDiagnosticValue(failure, () -> "hand=" + packet.getHand() + " clicked=" + clicked
            + " face=" + packet.getHitResult().getDirection()
            + " adjacent=" + clicked.relative(packet.getHitResult().getDirection()), "UNAVAILABLE");
        reportFirstPacketFailure(firstUseItemOnFailure, "ServerboundUseItemOnPacket", stage, player,
            binding, footprint, admission == null ? null : admission.ticket().generation(), failure,
            facts);
    }

    private void reportUseItemFailure(String stage, ServerPlayer player, ObjectOwnership binding,
                                      ServerboundUseItemPacket packet, RuntimeException failure) {
        ObjectOwnership current = binding == null ? currentPlayerBinding(player) : binding;
        String item = "UNAVAILABLE";
        if (player != null) {
            try { item = String.valueOf(BuiltInRegistries.ITEM.getKey(player.getItemInHand(packet.getHand()).getItem())); }
            catch (RuntimeException ignored) { item = "UNAVAILABLE"; }
        }
        reportFirstPacketFailure(firstUseItemFailure, "ServerboundUseItemPacket", stage, player,
            current, null, current == null ? null : current.topologyGeneration(), failure,
            "hand=" + packet.getHand() + " item=" + item);
    }

    private void reportFirstPacketFailure(java.util.concurrent.atomic.AtomicBoolean first, String packetClass,
            String stage, ServerPlayer player, ObjectOwnership binding, Set<RoutingKey> footprint,
            TopologyGeneration frozenGeneration, RuntimeException failure, String packetFacts) {
        if (!first.compareAndSet(false, true)) return;
        try {
            String world = player == null ? "UNAVAILABLE" : worldId(player.level()).value();
            String position = player == null ? "UNAVAILABLE" : player.position().toString();
            String owner = binding == null ? "UNAVAILABLE" : binding.owner().value();
            String incarnation = binding == null ? "UNAVAILABLE" : Long.toString(binding.incarnation());
            String objectEpoch = binding == null ? "UNAVAILABLE" : Long.toString(binding.objectEpoch().value());
            String movementEpoch = player == null ? "UNAVAILABLE"
                : Long.toString(movementTransitionEpochs.getOrDefault(player.getUUID().toString(), 0L));
            String generation = frozenGeneration == null ? "UNAVAILABLE" : Long.toString(frozenGeneration.value());
            Set<RoutingKey> missing = Set.of();
            if (binding != null) {
                FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, binding.owner());
                if (frozenGeneration == null) generation = Long.toString(authority.generation().value());
                if (failure instanceof ClosureEscapeException && footprint != null) {
                    java.util.LinkedHashSet<RoutingKey> escaped = new java.util.LinkedHashSet<>(footprint);
                    if (authority.wholeWorld()) escaped.removeIf(key -> key.world().equals(authority.region().world()));
                    else escaped.removeAll(authority.domain());
                    missing = Set.copyOf(escaped);
                }
            }
            String ownerContext = OwnershipContext.current().map(active -> active.region().value()
                + "/lease=" + active.lease().value() + "/category=" + active.category()).orElse("NONE");
            String serviceTurn = player == null ? "UNAVAILABLE"
                : dev.phoenium.chunk.FullFinishingServices.currentTurn(player.level().getServer());
            logger.warn("PHOENIUM_STAGE7 first-packet-rejection packet={} stage={} exceptionClass={} message={} world={} "
                    + "authoritativePosition={} owner={} incarnation={} objectEpoch={} movementEpoch={} requestedFootprint={} "
                    + "frozenGeneration={} missingKeys={} ownerContext={} serviceTurn={} facts=[{}]",
                packetClass, stage, failure.getClass().getName(), failure.getMessage(), world, position, owner,
                incarnation, objectEpoch, movementEpoch, footprint == null ? "NOT_APPLICABLE" : footprint,
                generation, failure instanceof ClosureEscapeException ? missing : "NOT_APPLICABLE",
                ownerContext, serviceTurn, packetFacts);
        } catch (RuntimeException diagnosticFailure) {
            suppressPacketDiagnosticFailure(failure, diagnosticFailure);
            try {
                logger.warn("PHOENIUM_STAGE7 first-packet-rejection packet={} stage={} exceptionClass={} message={} "
                        + "diagnosticFailure={}", packetClass, stage, failure.getClass().getName(),
                    failure.getMessage(), diagnosticFailure.toString());
            } catch (RuntimeException loggingFailure) {
                suppressPacketDiagnosticFailure(failure, loggingFailure);
            }
        }
    }

    private Set<RoutingKey> requestedBlockFootprint(ServerPlayer player, BlockPos target) {
        if (player == null || target == null) return Set.of();
        WorldId world = worldId(player.level());
        RoutingKey playerKey = new RoutingKey(world, player.chunkPosition().x(), player.chunkPosition().z());
        RoutingKey targetKey = new RoutingKey(world, target.getX() >> 4, target.getZ() >> 4);
        return blockPacketFootprint(playerKey, targetKey);
    }

    static Set<RoutingKey> blockPacketSeeds(RoutingKey playerKey, RoutingKey targetKey) {
        java.util.LinkedHashSet<RoutingKey> seeds = new java.util.LinkedHashSet<>(2);
        seeds.add(Objects.requireNonNull(playerKey, "playerKey"));
        seeds.add(Objects.requireNonNull(targetKey, "targetKey"));
        return java.util.Collections.unmodifiableSet(seeds);
    }

    static Set<RoutingKey> blockPacketFootprint(RoutingKey playerKey, RoutingKey targetKey) {
        return new TopologyPlanner().requiredClosure(blockPacketSeeds(playerKey, targetKey), 1);
    }

    static <T> T packetDiagnosticValue(RuntimeException primaryFailure,
                                       java.util.function.Supplier<T> diagnostic,
                                       T fallback) {
        try {
            return diagnostic.get();
        } catch (RuntimeException diagnosticFailure) {
            suppressPacketDiagnosticFailure(primaryFailure, diagnosticFailure);
            return fallback;
        }
    }

    private static void suppressPacketDiagnosticFailure(RuntimeException primaryFailure,
                                                        RuntimeException diagnosticFailure) {
        if (diagnosticFailure != primaryFailure) primaryFailure.addSuppressed(diagnosticFailure);
    }

    private ObjectOwnership currentPlayerBinding(ServerPlayer player) {
        return player == null ? null : objects.current(player.getUUID().toString()).orElse(null);
    }

    private static boolean isBlockPacketOperation(String operationKind) {
        return PLAYER_ACTION_OPERATION.equals(operationKind) || USE_ITEM_ON_OPERATION.equals(operationKind)
            || USE_ITEM_OPERATION.equals(operationKind);
    }

    /** Exactly one native discard, after current-owner and exact-incarnation validation. */
    private void executePearlDiscard(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
        ObjectOwnership current = objects.requireCurrentOwner(operation.objectId(), operation.expectedObjectEpoch(), runtime.id());
        PendingPearlRemoval pending = pendingPearlRemovals.get(operation.correlationId());
        if (pending == null) throw new IllegalStateException("missing admitted pearl cleanup " + operation.correlationId());
        PearlMembershipLedger.Membership membership = pending.membership();
        Entity entity = pending.entity();
        if (!pearlMemberships.isCurrent(membership) || !(entity instanceof ThrownEnderpearl)
                || !(entity instanceof FullEntityFacts.Access access)
                || !entity.getUUID().toString().equals(membership.objectId())
                || access.phoenium$entityIncarnation() != membership.incarnation()
                || entity.isRemoved()) {
            pendingPearlRemovals.remove(operation.correlationId(), pending);
            pearlMemberships.settleCleanup(membership);
            pearlAliases.remove(membership.identity(), entity);
            pearlCleanupRejections++;
            return;
        }
        entity.discard();
        pendingPearlRemovals.remove(operation.correlationId(), pending);
        pearlMemberships.settleCleanup(membership);
        pearlAliases.remove(membership.identity(), entity);
        pearlCleanupExecutions++;
    }

    /** Exactly one native discard, after current-owner and exact-incarnation validation. */
    private void executeProjectileDiscard(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
        ObjectOwnership current = objects.requireCurrentOwner(operation.objectId(), operation.expectedObjectEpoch(), runtime.id());
        PendingProjectileRemoval pending = pendingProjectileRemovals.get(operation.correlationId());
        if (pending == null) throw new IllegalStateException("missing admitted projectile cleanup " + operation.correlationId());
        ProjectileMembershipLedger.Membership membership = pending.membership();
        Entity entity = pending.entity();
        if (!projectileMemberships.isCurrent(membership) || !(entity instanceof Projectile)
                || !(entity instanceof FullEntityFacts.Access access)
                || !entity.getUUID().toString().equals(membership.objectId())
                || access.phoenium$entityIncarnation() != membership.incarnation()
                || entity.isRemoved()) {
            pendingProjectileRemovals.remove(operation.correlationId(), pending);
            projectileMemberships.settleCleanup(membership);
            projectileAliases.remove(membership.identity(), entity);
            projectileCleanupRejections++;
            return;
        }
        entity.discard();
        pendingProjectileRemovals.remove(operation.correlationId(), pending);
        projectileMemberships.settleCleanup(membership);
        projectileAliases.remove(membership.identity(), entity);
        projectileCleanupExecutions++;
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
    private void recordEntityRelocation(EntityIndexLifecycleAdapter.RelocationFact fact) {
        entityRelocations++;
        mobDecisions.relocate(fact.objectId(), fact.incarnation(), fact.newRegion());
        if (!projectileMemberships.settings().enabled()) return;
        scheduleProjectileCleanup(projectileMemberships.relocate(fact.objectId(), fact.incarnation(),
            fact.oldRegion(), fact.newRegion()));
    }
    private boolean acceptsGameplay() { return !gameplayAdmissionClosed && isRunning(); }
    private static WorldId worldId(ServerLevel level) { return new WorldId(level.dimension().identifier().toString()); }
    private static String projectileType(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? null : key.toString();
    }
    private static RegionRuntime readyRuntime(RegionId region) {
        RegionRuntime runtime = new RegionRuntime(region, new OwnerEpoch(0), false);
        runtime.transitionTo(RegionLifecycle.READY);
        return runtime;
    }
    /** Lifecycle callback after vanilla has made an entity addressable; it never performs a world lookup.
     * A saved reader can discard an entity before native storage inserts it. Native lookup and
     * spawn enumeration still include that exact insertion, so removed status cannot erase its
     * committed ownership lifetime. The later original removal callback retires the binding. */
    public void onEntityAddressable(Entity entity) {
        onEntityAddressable(entity, true);
    }

    /** Downstream of native spawn decisions: exact identity custody only, no tracker or policy admission. */
    public dev.phoenium.chunk.FreshPassengerAdmission naturalSpawnPassengerIdentity(ServerLevel level, Entity root) {
        requireRegionalServiceLifetime();
        return dev.phoenium.chunk.FreshPassengerAdmission.begin(level, root, freshPassengerPublisher(level));
    }
    /** Complete native natural-spawn attempt lifetime; nested attempts in the same world share custody. */
    public dev.phoenium.chunk.FreshPassengerAdmission.NaturalSpawnScope naturalSpawnPassengerScope(ServerLevel level) {
        requireRegionalServiceLifetime();
        return dev.phoenium.chunk.FreshPassengerAdmission.naturalSpawnScope(level, freshPassengerPublisher(level));
    }
    private dev.phoenium.chunk.FreshPassengerAdmission.Publisher freshPassengerPublisher(ServerLevel level) {
        WorldId world = worldId(level);
        return new dev.phoenium.chunk.FreshPassengerAdmission.Publisher() {
                @Override public void requireOwners(java.util.Set<net.minecraft.world.level.ChunkPos> positions) {
                    if (!isRunning() || regionalDispatcher == null)
                        throw new IllegalStateException("fresh passenger admission outside live owner lifetime");
                    var keys = positions.stream().map(pos -> new RoutingKey(world, pos.x(), pos.z()))
                        .collect(java.util.stream.Collectors.toSet());
                    regionalDispatcher.requireCurrentClosure(keys);
                }
                @Override public ObjectOwnership publish(Entity entity) {
                    RoutingKey key = new RoutingKey(world, entity.chunkPosition().x(), entity.chunkPosition().z());
                    regionalDispatcher.requireCurrentOwner(key);
                    return objects.admit(entity.getUUID().toString(), OwnedObjectKind.ENTITY, key);
                }
                @Override public ObjectOwnership current(String uuid) { return objects.current(uuid).orElse(null); }
                @Override public void retire(ObjectOwnership identity) { objects.retirePublication(identity); }
            };
    }
    /** Metadata-only first-failure context; never discovers a native entity through its UUID. */
    public String passengerOwnershipDiagnostic(String uuid) {
        try { return objects.current(uuid).map(Object::toString).orElse("ABSENT"); }
        catch (RuntimeException unavailable) { return "UNAVAILABLE: " + unavailable.getMessage(); }
    }

    /**
     * Bootstrap uses the same exact addressability edge but defers overflow delivery until every
     * loaded pearl has been collected and deterministically reconstructed.
     */
    private void onEntityAddressable(Entity entity, boolean enforcePearlPolicy) {
        if (!isRunning() || !(entity.level() instanceof ServerLevel level)) return;
        WorldId world = worldId(level);
        RoutingKey key = new RoutingKey(world, entity.chunkPosition().x(), entity.chunkPosition().z());
        ObjectOwnership binding;
        try {
            binding = entityIndexLifecycle.addressable(entity.getUUID().toString(),
                entity instanceof ServerPlayer ? OwnedObjectKind.PLAYER : OwnedObjectKind.ENTITY,
                world, entity.getId(), key, explicitFixture);
        } catch (RuntimeException unavailable) {
            // A target outside the finite explicit fixture has no derived candidate.  It cannot be
            // rediscovered from the ServerLevel index at packet ingress.
            return;
        }
        NativePortalTransfer.addressable(entity, binding);
        observePearlAddress(entity, binding, enforcePearlPolicy);
        observeMobDecision(entity, binding);
        if (projectileMemberships.settings().enabled())
            observeProjectileAddress(entity, binding, enforcePearlPolicy);
    }

    private void observeProjectileAddress(Entity entity, ObjectOwnership binding, boolean enforcePolicy) {
        if (!projectileMemberships.settings().enabled() || !(entity instanceof Projectile)
                || binding.kind() != OwnedObjectKind.ENTITY
                || !(entity instanceof FullEntityFacts.Access access)) return;
        String projectileType = projectileType(entity);
        if (projectileType == null) return;
        ProjectileMembershipLedger.Admission admission = projectileMemberships.admit(
            new ProjectileMembershipLedger.Scope(binding.owner(), projectileType), entity.getUUID().toString(),
            access.phoenium$entityIncarnation());
        projectileAliases.put(admission.membership().identity(), entity);
        if (enforcePolicy) scheduleProjectileCleanup(admission.cleanup());
    }

    private void observeMobDecision(Entity entity, ObjectOwnership binding) {
        if (!mobDecisions.settings().enabled() || !(entity instanceof net.minecraft.world.entity.Mob)
                || !(entity instanceof FullEntityFacts.Access access)) return;
        var category = MobDecisionContext.category(entity.getClass());
        if (category != null) mobDecisions.observe(entity.getUUID().toString(), access.phoenium$entityIncarnation(),
            binding.incarnation(), new MobDecisionScheduler.Scope(binding.owner(), category));
    }

    public boolean allowMobDecision(net.minecraft.world.entity.Mob mob) {
        if (!mobDecisions.settings().enabled() || !isRunning()) return true;
        var category = MobDecisionContext.category(mob.getClass());
        if (category == null || !(mob instanceof FullEntityFacts.Access access)
                || !(mob.level() instanceof ServerLevel level)) return true;
        String id = mob.getUUID().toString();
        ObjectOwnership binding = objects.current(id).orElse(null);
        if (binding == null) return true; // Normal native admission, never policy metadata, owns safety.
        var active = OwnershipContext.current().orElseThrow(() -> new IllegalStateException("mob decision without native owner"));
        objects.requireCurrentOwner(id, binding.objectEpoch(), active.region());
        var scope = new MobDecisionScheduler.Scope(binding.owner(), category);
        mobDecisions.observe(id, access.phoenium$entityIncarnation(), binding.incarnation(), scope);
        return mobDecisions.allow(id, access.phoenium$entityIncarnation(), scope, mob.tickCount,
            level.getGameTime(), mob.isPassenger() || mob.isVehicle());
    }

    public MobDecisionScheduler.Snapshot mobDecisionSnapshot() { return mobDecisions.snapshot(); }

    private void observePearlAddress(Entity entity, ObjectOwnership binding, boolean enforcePearlPolicy) {
        if (!(entity instanceof ThrownEnderpearl pearl) || binding.kind() != OwnedObjectKind.ENTITY
                || !(entity.level() instanceof ServerLevel level) || !(entity instanceof FullEntityFacts.Access access)) return;
        java.util.UUID playerId = PearlOwnerAccess.playerId(pearl);
        if (playerId == null) return;
        WorldId world = worldId(level);
        PearlMembershipLedger.Admission admission = pearlMemberships.admit(
            new PearlMembershipLedger.Scope(world, playerId), entity.getUUID().toString(), access.phoenium$entityIncarnation());
        pearlAliases.put(admission.membership().identity(), entity);
        if (enforcePearlPolicy) schedulePearlCleanup(admission.cleanup());
    }

    private void scheduleProjectileCleanup(List<ProjectileMembershipLedger.Membership> memberships) {
        for (ProjectileMembershipLedger.Membership membership : memberships) {
            Entity entity = projectileAliases.get(membership.identity());
            if (entity == null) {
                deferredProjectileCleanups.remove(membership.identity());
                projectileMemberships.settleCleanup(membership);
                projectileCleanupRejections++;
                continue;
            }
            scheduleProjectileCleanup(membership, entity);
        }
    }

    /**
     * Queues identity only; the live alias remains in this bounded integration table. A temporary
     * absence from the current-owner registry is a retryable relocation state, not a terminal
     * rejection while the native projectile is still live.
     */
    private void scheduleProjectileCleanup(ProjectileMembershipLedger.Membership membership, Entity entity) {
        if (!projectileMemberships.isCurrent(membership)) {
            deferredProjectileCleanups.remove(membership.identity());
            return;
        }
        if (!isRunning() || objectGateway == null || entity.isRemoved()) {
            deferredProjectileCleanups.remove(membership.identity());
            projectileMemberships.settleCleanup(membership);
            projectileAliases.remove(membership.identity(), entity);
            projectileCleanupRejections++;
            return;
        }
        ObjectOwnership current = objects.current(membership.objectId()).orElse(null);
        if (current == null) {
            deferredProjectileCleanups.put(membership.identity(), new DeferredProjectileCleanup(entity, membership));
            return;
        }
        if (current.kind() != OwnedObjectKind.ENTITY) {
            deferredProjectileCleanups.remove(membership.identity());
            projectileMemberships.settleCleanup(membership);
            projectileAliases.remove(membership.identity(), entity);
            projectileCleanupRejections++;
            return;
        }
        deferredProjectileCleanups.remove(membership.identity());
        TaskCorrelationId correlation = new TaskCorrelationId(++sequence);
        PendingProjectileRemoval pending = new PendingProjectileRemoval(entity, membership);
        pendingProjectileRemovals.put(correlation, pending);
        ObjectOwnerGateway.Delivery delivery;
        try {
            delivery = objectGateway.submit(new OwnedObjectEnvelope(current.objectId(), current.objectEpoch(),
                ProjectileMembershipLedger.DISCARD_OPERATION, "phoenium-projectile-cap", correlation,
                new TaskSequence(sequence), AdmissionPolicy.REROUTE));
        } catch (RuntimeException | Error failure) {
            pendingProjectileRemovals.remove(correlation, pending);
            projectileMemberships.settleCleanup(membership);
            projectileAliases.remove(membership.identity(), entity);
            projectileCleanupRejections++;
            throw failure;
        }
        if (delivery == ObjectOwnerGateway.Delivery.QUEUED || delivery == ObjectOwnerGateway.Delivery.REROUTED) {
            projectileCleanupAdmissions++;
        } else if (delivery == ObjectOwnerGateway.Delivery.REJECTED_ABSENT && !entity.isRemoved()
                && projectileMemberships.isCurrent(membership)) {
            pendingProjectileRemovals.remove(correlation, pending);
            deferredProjectileCleanups.put(membership.identity(), new DeferredProjectileCleanup(entity, membership));
        } else {
            pendingProjectileRemovals.remove(correlation, pending);
            projectileMemberships.settleCleanup(membership);
            projectileAliases.remove(membership.identity(), entity);
            projectileCleanupRejections++;
        }
    }

    /** Retries only already-selected cleanup identities after the owner-free relocation cut. */
    private void retryDeferredProjectileCleanups() {
        if (OwnershipContext.current().isPresent() || deferredProjectileCleanups.isEmpty()) return;
        for (DeferredProjectileCleanup deferred : java.util.List.copyOf(deferredProjectileCleanups.values())) {
            ProjectileMembershipLedger.ProjectileIdentity identity = deferred.membership().identity();
            if (deferredProjectileCleanups.get(identity) != deferred) continue;
            if (!projectileMemberships.isCurrent(deferred.membership())) {
                deferredProjectileCleanups.remove(identity, deferred);
                continue;
            }
            Entity entity = projectileAliases.get(identity);
            if (entity == null || entity.isRemoved()) {
                deferredProjectileCleanups.remove(identity, deferred);
                projectileMemberships.settleCleanup(deferred.membership());
                projectileCleanupRejections++;
                continue;
            }
            scheduleProjectileCleanup(deferred.membership(), entity);
        }
    }

    private void schedulePearlCleanup(List<PearlMembershipLedger.Membership> memberships) {
        for (PearlMembershipLedger.Membership membership : memberships) {
            Entity entity = pearlAliases.get(membership.identity());
            if (entity == null) {
                deferredPearlCleanups.remove(membership.identity());
                pearlMemberships.settleCleanup(membership);
                pearlCleanupRejections++;
                continue;
            }
            schedulePearlCleanup(membership, entity);
        }
    }

    /**
     * Queues identity only; the live alias remains in this bounded integration table. A temporary
     * absence from the current-owner registry is a retryable topology/relocation state, not a
     * terminal cleanup rejection while the native pearl is still live.
     */
    private void schedulePearlCleanup(PearlMembershipLedger.Membership membership, Entity entity) {
        if (!pearlMemberships.isCurrent(membership)) {
            deferredPearlCleanups.remove(membership.identity());
            return;
        }
        if (!isRunning() || objectGateway == null || entity.isRemoved()) {
            deferredPearlCleanups.remove(membership.identity());
            pearlMemberships.settleCleanup(membership);
            pearlAliases.remove(membership.identity(), entity);
            pearlCleanupRejections++;
            return;
        }
        ObjectOwnership current = objects.current(membership.objectId()).orElse(null);
        if (current == null) {
            deferredPearlCleanups.put(membership.identity(), new DeferredPearlCleanup(entity, membership));
            return;
        }
        // ObjectOwnership.incarnation is the registry's UUID-reuse counter; the pearl membership
        // incarnation is FullEntityFacts' exact native lifecycle identity. They intentionally live
        // in different domains. The owner gateway already revalidates objectId/objectEpoch, while
        // the native identity check above validates the pearl's FullEntityFacts incarnation.
        if (current.kind() != OwnedObjectKind.ENTITY) {
            deferredPearlCleanups.remove(membership.identity());
            pearlMemberships.settleCleanup(membership);
            pearlAliases.remove(membership.identity(), entity);
            pearlCleanupRejections++;
            return;
        }
        deferredPearlCleanups.remove(membership.identity());
        TaskCorrelationId correlation = new TaskCorrelationId(++sequence);
        PendingPearlRemoval pending = new PendingPearlRemoval(entity, membership);
        pendingPearlRemovals.put(correlation, pending);
        ObjectOwnerGateway.Delivery delivery;
        try {
            delivery = objectGateway.submit(new OwnedObjectEnvelope(current.objectId(), current.objectEpoch(),
                PearlMembershipLedger.DISCARD_OPERATION, "phoenium-pearl-cap", correlation,
                new TaskSequence(sequence), AdmissionPolicy.REROUTE));
        } catch (RuntimeException | Error failure) {
            pendingPearlRemovals.remove(correlation, pending);
            pearlMemberships.settleCleanup(membership);
            pearlAliases.remove(membership.identity(), entity);
            pearlCleanupRejections++;
            throw failure;
        }
        if (delivery == ObjectOwnerGateway.Delivery.QUEUED || delivery == ObjectOwnerGateway.Delivery.REROUTED) {
            pearlCleanupAdmissions++;
        } else if (delivery == ObjectOwnerGateway.Delivery.REJECTED_ABSENT && !entity.isRemoved()
                && pearlMemberships.isCurrent(membership)) {
            pendingPearlRemovals.remove(correlation, pending);
            deferredPearlCleanups.put(membership.identity(), new DeferredPearlCleanup(entity, membership));
        } else {
            pendingPearlRemovals.remove(correlation, pending);
            pearlMemberships.settleCleanup(membership);
            pearlAliases.remove(membership.identity(), entity);
            pearlCleanupRejections++;
        }
    }

    /** Retries only already-selected cleanup identities after the owner-free relocation cut. */
    private void retryDeferredPearlCleanups() {
        if (OwnershipContext.current().isPresent() || deferredPearlCleanups.isEmpty()) return;
        for (DeferredPearlCleanup deferred : java.util.List.copyOf(deferredPearlCleanups.values())) {
            PearlMembershipLedger.PearlIdentity identity = deferred.membership().identity();
            if (deferredPearlCleanups.get(identity) != deferred) continue;
            if (!pearlMemberships.isCurrent(deferred.membership())) {
                deferredPearlCleanups.remove(identity, deferred);
                continue;
            }
            Entity entity = pearlAliases.get(identity);
            if (entity == null || entity.isRemoved()) {
                deferredPearlCleanups.remove(identity, deferred);
                pearlMemberships.settleCleanup(deferred.membership());
                pearlCleanupRejections++;
                continue;
            }
            schedulePearlCleanup(deferred.membership(), entity);
        }
    }

    /** Non-player cross-owner movement is observed here and committed later at the owner-free tick boundary. */
    public void onEntityRoutingObserved(Entity entity) {
        if (!isRunning() || !(entity.level() instanceof ServerLevel level) || entity.isRemoved()) return;
        WorldId world = worldId(level);
        RoutingKey key = new RoutingKey(world, entity.chunkPosition().x(), entity.chunkPosition().z());
        entityIndexLifecycle.routingObserved(entity.getUUID().toString(), world, entity.getId(), key);
    }

    /** Removal invalidates the network-id candidate before vanilla can later reuse its numeric ID. */
    public void onEntityRemoved(Entity entity) {
        onEntityRemoved(entity, null);
    }

    /**
     * A pearl has a non-terminal player-disconnect lifecycle in 26.2: vanilla deliberately keeps
     * its persisted owner association for {@code UNLOADED_WITH_PLAYER}.  Preserve the derived
     * membership at that edge; all terminal/removal and world-unload reasons retire the exact
     * incarnation before the normal object binding is removed.
     */
    public void onEntityRemoved(Entity entity, Entity.RemovalReason reason) {
        if (!isRunning() || !(entity.level() instanceof ServerLevel level)) return;
        if (NativePortalTransfer.expectedRemoval(entity, reason)) return;
        if (itemPortalTransitions.onRemoval(entity, reason)) return;
        if (entity instanceof ServerPlayer player && playerDimensionTransitions.onRemoval(player, reason)) return;
        if (pearlPortalTransitions.onRemoval(entity, reason)) return;
        retireEntityLifecycle(entity, reason);
    }

    private void retireEntityLifecycle(Entity entity, Entity.RemovalReason reason) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (entity instanceof FullEntityFacts.Access access)
            mobDecisions.retire(entity.getUUID().toString(), access.phoenium$entityIncarnation());
        if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) preservePearlMembershipForReconnect(entity);
        else retirePearlMembership(entity);
        retireProjectileMembership(entity);
        entityIndexLifecycle.removed(entity.getUUID().toString(), worldId(level), entity.getId());
    }

    public void beginPearlPortalTick(ThrownEnderpearl pearl, int previousSectionX, int previousSectionZ, Entity retainedOwner) {
        pearlPortalTransitions.beginTick(pearl, previousSectionX, previousSectionZ, retainedOwner);
    }

    public void beginItemPortalTick(net.minecraft.world.entity.item.ItemEntity item) { itemPortalTransitions.begin(item); }
    public boolean captureItemPortal(net.minecraft.world.entity.item.ItemEntity item, net.minecraft.world.level.portal.TeleportTransition transition) {
        return itemPortalTransitions.capture(item, transition);
    }
    public boolean itemPortalSuspended(net.minecraft.world.entity.item.ItemEntity item) { return itemPortalTransitions.suspended(item); }
    public boolean completeItemPortalTick(net.minecraft.world.entity.item.ItemEntity item) { return itemPortalTransitions.complete(item); }
    public void finishItemPortalTick(net.minecraft.world.entity.item.ItemEntity item) { itemPortalTransitions.finish(item); }

    public void retainPearlPortalHitResult(ThrownEnderpearl pearl, net.minecraft.world.phys.HitResult result) {
        pearlPortalTransitions.retainHitResult(pearl, result);
    }

    public boolean suspendPearlPortalDestination(ThrownEnderpearl pearl, PortalProcessor processor) {
        return pearlPortalTransitions.suspendDestination(pearl, processor);
    }

    public boolean pearlPortalTickSuspended(ThrownEnderpearl pearl) { return pearlPortalTransitions.suspended(pearl); }
    public boolean completePearlPortalTick(ThrownEnderpearl pearl) { return pearlPortalTransitions.completeTick(pearl); }
    public void finishPearlPortalTick(ThrownEnderpearl pearl) { pearlPortalTransitions.finishTick(pearl); }
    public boolean skipPendingPearlPortalTick(ThrownEnderpearl pearl) { return pearlPortalTransitions.skipPendingTick(pearl); }

    public boolean beginPlayerDimensionTransition(ServerPlayer player,
            net.minecraft.world.level.portal.TeleportTransition transition) {
        return playerDimensionTransitions.begin(player, transition);
    }

    public boolean suspendPlayerDimensionTransition(ServerPlayer player) {
        return playerDimensionTransitions.suspendAfterSourceRemoval(player);
    }

    public boolean finishPlayerDimensionTransitionCall(ServerPlayer player, boolean began) {
        return playerDimensionTransitions.finishNativeCall(player, began);
    }

    public boolean playerDimensionTransitionPending(ServerPlayer player) {
        return playerDimensionTransitions.pending(player);
    }

    public boolean retainPearlPlayerTeleportSuffix(ThrownEnderpearl pearl, ServerLevel destination,
                                                    net.minecraft.world.phys.Vec3 teleportPosition,
                                                    ServerPlayer player) {
        return playerDimensionTransitions.retainPearlHit(pearl, destination, teleportPosition, player);
    }

    public void onWorldClosing(ServerLevel level) {
        dev.phoenium.chunk.FullFinishingServices.passengerWorldClosing(level);
        playerDimensionTransitions.worldClosing(level);
        pearlPortalTransitions.worldClosing(level);
        itemPortalTransitions.worldClosing(level);
    }

    private void preservePearlMembershipForReconnect(Entity entity) {
        if (!(entity instanceof ThrownEnderpearl) || !(entity instanceof FullEntityFacts.Access access)) return;
        String objectId = entity.getUUID().toString();
        PearlMembershipLedger.PearlIdentity identity = new PearlMembershipLedger.PearlIdentity(
            objectId, access.phoenium$entityIncarnation());
        deferredPearlCleanups.remove(identity);
        if (pearlMemberships.preserveForReconnect(objectId, access.phoenium$entityIncarnation()))
            pearlAliases.remove(identity, entity);
    }

    private void retirePearlMembership(Entity entity) {
        if (!(entity instanceof ThrownEnderpearl) || !(entity instanceof FullEntityFacts.Access access)) return;
        PearlMembershipLedger.PearlIdentity identity = new PearlMembershipLedger.PearlIdentity(
            entity.getUUID().toString(), access.phoenium$entityIncarnation());
        deferredPearlCleanups.remove(identity);
        pearlMemberships.retire(identity.objectId(), identity.incarnation());
        pearlAliases.remove(identity, entity);
    }

    private void retireProjectileMembership(Entity entity) {
        if (!projectileMemberships.settings().enabled()
                || !(entity instanceof Projectile) || !(entity instanceof FullEntityFacts.Access access)) return;
        ProjectileMembershipLedger.ProjectileIdentity identity = new ProjectileMembershipLedger.ProjectileIdentity(
            entity.getUUID().toString(), access.phoenium$entityIncarnation());
        deferredProjectileCleanups.remove(identity);
        projectileMemberships.retire(identity.objectId(), identity.incarnation());
        projectileAliases.remove(identity, entity);
    }

    /** PlayerList placement is the required initial-ownership point before the first movement packet. */
    public void onPlayerPlaced(ServerPlayer player) {
        if (!isRunning()) return;
        String objectId = player.getUUID().toString();
        movementTransitionFences.remove(objectId);
        movementTransitionEpochs.remove(objectId);
        bindPlayer(player);
    }

    /**
     * A native dimension change moves the existing ServerPlayer object between world instances
     * without invoking PlayerList placement. Retain that exact identity as a bounded relocation;
     * the owner-free coordinator commits the destination-world owner before movement replay.
     */
    public void onPlayerLevelChanged(ServerPlayer player) {
        if (!isRunning() || !(player.level() instanceof ServerLevel level)) return;
        String objectId = player.getUUID().toString();
        for (var entry : packets.entrySet()) {
            if (entry.getValue() instanceof PendingMove move && move.objectId().equals(objectId)
                    && move.playerLevel() != level)
                invalidatedMovementPackets.add(entry.getKey());
        }
        MovementTransitionFence transition = movementTransitionFences.get(objectId);
        if (transition != null) {
            movementTransitionEpochs.merge(objectId, 1L, Long::sum);
            movementTransitionFences.put(objectId, new MovementTransitionFence(worldId(level)));
        }
        if (objects.current(objectId).isEmpty()) {
            try { bindPlayer(player); } catch (RuntimeException ignored) { }
            return;
        }
        WorldId world = worldId(level);
        RoutingKey key = new RoutingKey(world, player.chunkPosition().x(), player.chunkPosition().z());
        entityIndexLifecycle.routingObserved(objectId, world, player.getId(), key);
    }

    /** Invalidates old-world movement before ServerPlayer.teleport starts removing/re-adding it. */
    public void onPlayerLevelChanging(ServerPlayer player, ServerLevel destination) {
        if (!isRunning() || destination == null || player.level() == destination) return;
        String objectId = player.getUUID().toString();
        WorldId destinationWorld = worldId(destination);
        MovementTransitionFence existing = movementTransitionFences.get(objectId);
        // ServerPlayer.teleport(TeleportTransition), Entity.setLevel, and the direct
        // ServerPlayer.teleportTo overload can all observe the same native transition. The first
        // callback owns the lifecycle edge; nested callbacks must not advance the fence again.
        if (existing != null && existing.destinationWorld().equals(destinationWorld)) return;
        movementTransitionEpochs.merge(objectId, 1L, Long::sum);
        movementTransitionFences.put(objectId, new MovementTransitionFence(destinationWorld));
        for (var entry : packets.entrySet()) {
            if (entry.getValue() instanceof PendingMove move && move.objectId().equals(objectId))
                invalidatedMovementPackets.add(entry.getKey());
        }
    }

    /** Lease-bound reconciliation remains a startup/audit backstop, not packet-ingress target discovery. */
    private void refreshEntityBindings(ServerLevel level) {
        WorldId world = worldId(level);
        HashSet<String> present = new HashSet<>();
        for (Entity entity : level.getAllEntities()) {
            String id = entity.getUUID().toString();
            present.add(id);
            onEntityAddressable(entity, false);
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

    private sealed interface PendingPacket permits PendingMove, PendingInteract, PendingBlockPacket, PendingUseItem {
        void execute();
    }
    private record PendingMove(PhoeniumLiveRuntime runtime, ServerGamePacketListenerImpl connection, ServerboundMovePlayerPacket packet,
                               ServerLevel playerLevel,
                               String objectId, long playerIncarnation, long transitionEpoch,
                               LiveMovementAdmission.Ticket ticket) implements PendingPacket {
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
    private sealed interface PendingBlockPacket extends PendingPacket permits PendingPlayerAction, PendingUseItemOn {
        ServerGamePacketListenerImpl connection();
        ServerLevel playerLevel();
        String objectId();
        long playerIncarnation();
        long transitionEpoch();
        LiveBlockPacketAdmission.Ticket ticket();
    }
    private record PendingPlayerAction(ServerGamePacketListenerImpl connection, ServerboundPlayerActionPacket packet,
                                       ServerLevel playerLevel, String objectId, long playerIncarnation,
                                       long transitionEpoch, LiveBlockPacketAdmission.Ticket ticket)
            implements PendingBlockPacket {
        @Override public void execute() { ((ServerGamePacketListenerInvoker) connection).phoenium$invokePlayerAction(packet); }
    }
    private record PendingUseItemOn(ServerGamePacketListenerImpl connection, ServerboundUseItemOnPacket packet,
                                    ServerLevel playerLevel, String objectId, long playerIncarnation,
                                    long transitionEpoch, LiveBlockPacketAdmission.Ticket ticket)
            implements PendingBlockPacket {
        @Override public void execute() { ((ServerGamePacketListenerInvoker) connection).phoenium$invokeUseItemOn(packet); }
    }
    private record PendingUseItem(ServerGamePacketListenerImpl connection, ServerboundUseItemPacket packet,
                                  ServerLevel playerLevel, String objectId, long playerIncarnation,
                                  long transitionEpoch, LivePlayerPacketAdmission.Ticket ticket) implements PendingPacket {
        @Override public void execute() { ((ServerGamePacketListenerInvoker) connection).phoenium$invokeUseItem(packet); }
    }
    private record BlockPacketAdmission(ObjectOwnership binding, long transitionEpoch,
                                        LiveBlockPacketAdmission.Ticket ticket) { }
    private record PlayerPacketAdmission(ObjectOwnership binding, long transitionEpoch,
                                         LivePlayerPacketAdmission.Ticket ticket) { }
    private record PacketAdmissionResult(boolean admitted, ObjectOwnerGateway.Delivery delivery) { }
    private static final class PacketAdmissionStateException extends IllegalStateException {
        private PacketAdmissionStateException(String message) { super(message); }
    }
    private static final class PacketDeliveryRejectedException extends IllegalStateException {
        private PacketDeliveryRejectedException(ObjectOwnerGateway.Delivery delivery) {
            super(delivery == null ? "owner gateway rejected retained packet during revalidation"
                : "owner gateway rejected packet: " + delivery);
        }
    }
    private record PendingPearlRemoval(Entity entity, PearlMembershipLedger.Membership membership) { }
    private record DeferredPearlCleanup(Entity entity, PearlMembershipLedger.Membership membership) { }
    private record PendingProjectileRemoval(Entity entity, ProjectileMembershipLedger.Membership membership) { }
    private record DeferredProjectileCleanup(Entity entity, ProjectileMembershipLedger.Membership membership) { }
    private record MovementTransitionFence(WorldId destinationWorld) {
        private static final double MAX_REBASE_DISTANCE = 8.0D;

        private boolean near(double currentX, double currentY, double currentZ,
                             double requestedX, double requestedY, double requestedZ) {
            double dx = requestedX - currentX;
            double dy = requestedY - currentY;
            double dz = requestedZ - currentZ;
            return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                && dx * dx + dy * dy + dz * dz <= MAX_REBASE_DISTANCE * MAX_REBASE_DISTANCE;
        }
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
    private enum ReplayKind { MOVE, INTERACT, PLAYER_ACTION, USE_ITEM_ON, USE_ITEM }
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
        boolean runEntityBody;
        enum Step { PREFIX_OWNER, SCHEDULED_COORDINATOR, MIDDLE_OWNER, READINESS_COORDINATOR,
            CACHE_OWNER, BLOCK_EVENTS_COORDINATOR, FINAL_PREFIX_OWNER, FINAL_DECISION_COORDINATOR,
            ENTITY_MANAGEMENT_COORDINATOR, FINAL_OWNER, DONE }
    }
    public enum State { NEW, RUNNING, STOPPED }
}
