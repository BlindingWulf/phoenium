package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.mixin.ChunkMapLoadAccess;
import dev.phoenium.mixin.GenerationChunkHolderAccess;
import dev.phoenium.mixin.PoiStorageAccess;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import dev.phoenium.service.RegionalServiceDispatcher;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;

/** Production Decision-A binding for the exact Minecraft 26.2 EMPTY load graph. */
public final class ChunkBindingRuntime {
    public interface Authority {
        WorldAuthority requireChunkWorld(ServerLevel level);
        void chunkBarrier(Throwable failure);
        default RegionalServiceDispatcher regionalDispatcher() {
            throw new IllegalStateException("regional dispatcher is unavailable for this chunk binding authority");
        }
    }
    public record WorldAuthority(WorldId world, long worldInstanceSequence,
                                 ChunkPrerequisiteServices services, RegionDirectory directory,
                                 java.util.function.Function<RegionId, RegionLeaseManager> leases, FailureBarrier barrier) {
        public WorldAuthority(WorldId world, long worldInstanceSequence, ChunkPrerequisiteServices services,
                              RegionDirectory directory, Map<RegionId, RegionLeaseManager> leases, FailureBarrier barrier) {
            this(world, worldInstanceSequence, services, directory, Map.copyOf(leases)::get, barrier);
        }
        public WorldAuthority {
            Objects.requireNonNull(world); Objects.requireNonNull(services); Objects.requireNonNull(directory);
            Objects.requireNonNull(leases); Objects.requireNonNull(barrier);
            if (worldInstanceSequence < 0) throw new IllegalArgumentException("negative world instance sequence");
        }
    }
    private record ReservationEvidence(AtomicReference<ChunkStatus> startedWork,
                                       AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> statusSlots) { }
    private record Delivery(RegionId owner, TopologyGeneration generation, PendingVanillaStage<?, ?> stage) { }
    private record ServiceCounts(long lightBatches, long lightOccurrences, long lightReceipts, long poiEntries,
                                 long metadataEntries, long lifecycleEntries, long workEntries, long custodyEntries) { }

    private final Authority authority;
    private final Logger logger;
    /** Exact-reference indexes and graph membership only; never held across Minecraft callbacks. */
    private final ReentrantLock indexGate = new ReentrantLock();
    private final IdentityHashMap<CompletableFuture<ChunkAccess>, ChunkLoadGraph> returnedIndex = new IdentityHashMap<>();
    private final IdentityHashMap<CompletableFuture<?>, ChunkLoadGraph> poiIndex = new IdentityHashMap<>();
    private final IdentityHashMap<GenerationChunkHolder, Set<ChunkLoadGraph>> holderGraphs = new IdentityHashMap<>();
    /** Provider-lineage index metadata only; never held across provider callbacks or owner execution. */
    private final ReentrantLock c2meGate = new ReentrantLock();
    private final IdentityHashMap<Object, C2meGenerationGraph> c2meSlots = new IdentityHashMap<>();
    private final Set<C2meGenerationGraph> c2meGenerationGraphs =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<Object, C2meFullGraph> c2meFullSlots = new IdentityHashMap<>();
    private final Set<C2meFullGraph> c2meFullGraphs = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<Object, C2meAsyncReadGraph> c2meReadSlots = new IdentityHashMap<>();
    private final IdentityHashMap<Object, C2meAsyncReadGraph> c2meReadContexts = new IdentityHashMap<>();
    private final IdentityHashMap<net.minecraft.world.level.chunk.ProtoChunk, C2meAsyncReadGraph> c2meReadResults =
            new IdentityHashMap<>();
    private final Set<C2meAsyncReadGraph> c2meReadGraphs = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<WorldId, WorldBinding> worlds = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Delivery> deliveries = new ConcurrentLinkedDeque<>();
    private volatile boolean open = true;
    private final AtomicLong batchSequence = new AtomicLong();
    private long c2meReservationSequence;
    private long registrations, exactCaptures, poiInstalls, parsedApplications, recoveryApplications;
    private long publications, privateUnloads, staleReroutes, duplicateApplications, barriers;
    private long c2meRegistrations, c2meApplications, c2meControls, c2meProducerFailures;
    private long c2meFullRegistrations, c2meFullApplications, c2meFullPublications, c2meSendApplications;
    private long c2meReadRegistrations, c2meReadApplications, c2meReadProviderFailures;

    public ChunkBindingRuntime(Authority authority, Logger logger) {
        this.authority = Objects.requireNonNull(authority); this.logger = Objects.requireNonNull(logger);
    }

    /** Replaces only ChunkMap.applyStep's exact EMPTY scheduleChunkLoad call before the legacy graph exists. */
    public CompletableFuture<ChunkAccess> register(ChunkMap map, GenerationChunkHolder holder,
                                                    ChunkStep step, ChunkPos position) {
        Objects.requireNonNull(map); Objects.requireNonNull(holder); Objects.requireNonNull(step); Objects.requireNonNull(position);
        if (!open) return failed(new ChunkProtocolException("chunk binding is shut down"));
        if (step.targetStatus() != ChunkStatus.EMPTY || holder.getPos() != position)
            throw breach(new ChunkProtocolException("EMPTY registrar argument identity changed"));

        ChunkMapLoadAccess access = (ChunkMapLoadAccess) (Object) map;
        ServerLevel level = access.phoenium$level();
        WorldBinding world = world(authority.requireChunkWorld(level), map, access.phoenium$poi());
        GenerationChunkHolderAccess holderAccess = (GenerationChunkHolderAccess) holder;
        AtomicReference<ChunkStatus> started = holderAccess.phoenium$startedWork();
        AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> slots = holderAccess.phoenium$futures();
        if (started.get() != ChunkStatus.EMPTY)
            throw breach(new ChunkProtocolException("registrar did not receive the winning EMPTY reservation"));

        RoutingKey key = new RoutingKey(world.authority.world(), position.x(), position.z());
        Optional<ChunkCustodyModel.LifecycleId> prior = world.authority.services().lifecycles()
                .resolveCurrentLifecycle(world.authority.worldInstanceSequence(), key);
        ChunkCustodyModel.LifecycleId lifecycle = world.authority.services().lifecycles()
                .activateHolder(world.authority.worldInstanceSequence(), key, holder);
        if (prior.isPresent() && !prior.orElseThrow().equals(lifecycle)) supersedeLifecycle(prior.orElseThrow());
        ChunkCustodyModel.WorkId work = world.authority.services().works().beginOrResolveSharedWork(lifecycle,
                new ChunkWorkRegistry.WorkReservation("generation-status", "EMPTY", 0));
        world.authority.services().trackHolder(lifecycle, holder);
        ReservationEvidence reservation = new ReservationEvidence(started, slots);
        ChunkLoadGraph.Token token = new ChunkLoadGraph.Token(lifecycle, work, holder, reservation);
        LoadEnvironment environment = new LoadEnvironment(world, access, position, step, reservation);
        ChunkLoadGraph graph = new ChunkLoadGraph(environment, token);
        bindHolderGraph(holder, graph);

        try {
            CompletableFuture<Optional<SerializableChunkData>> parsed = access.phoenium$read(position)
                    .thenApplyAsync(data -> access.phoenium$parse(position, data),
                            Util.backgroundExecutor().forName("parseChunk"));
            CompletableFuture<?> poi = access.phoenium$poi().prefetch(position);
            bindPoi(poi, graph);
            CompletableFuture<Void> installedPoi = graph.registerPoi(poi, access.phoenium$mainExecutor());
            CompletableFuture<Optional<SerializableChunkData>> upstream = parsed.thenCombine(installedPoi,
                    ChunkMapLoadAccess::phoenium$combine);
            CompletableFuture<ChunkAccess> returned = graph.register(upstream, access.phoenium$mainExecutor());
            bindReturned(returned, graph);
            registrations++;
            trace("REGISTERED", graph, "holder=" + holder.getPos() + " R=" + returned);
            return returned;
        } catch (Throwable failure) {
            graph.shutdown();
            throw breach(failure);
        }
    }

    /** Exact adjacent R.handle edge. A missing identity means this is an untouched non-EMPTY graph. */
    public CompletableFuture<ChunkResult<ChunkAccess>> attachIfBound(CompletableFuture<ChunkAccess> returned,
                                                                     GenerationChunkHolder holder, ChunkStep step) {
        ChunkLoadGraph graph;
        indexGate.lock();
        try { graph = returnedIndex.remove(returned); }
        finally { indexGate.unlock(); }
        if (graph == null) return null;
        if (step.targetStatus() != ChunkStatus.EMPTY || graph.token().holder() != holder)
            throw breach(new ChunkProtocolException("exact returned-future holder edge changed"));
        exactCaptures++;
        trace("HOLDER_BOUND", graph, "R=" + returned);
        return graph.attachHolder(returned, holder);
    }

    /**
     * C2ME keeps ownership of the asynchronous pre-FULL calculation. Phoenium gates only the
     * provider-facing completion on the exact backing status slot and current owner lease.
     */
    public CompletableFuture<ChunkAccess> c2meGeneration(Object delegate, Object context, ChunkStep step,
                                                          ChunkAccess input,
                                                          Supplier<CompletableFuture<ChunkAccess>> original) {
        Objects.requireNonNull(step); Objects.requireNonNull(input); Objects.requireNonNull(original);
        if (!open) return failed(new C2meGenerationControlException(ChunkStageAdmission.Result.SHUTDOWN));

        final C2meGenerationAccess.Observation observation;
        final WorldBinding world;
        try {
            observation = C2meGenerationAccess.capture(delegate, context, input.getPos());
            if (step.targetStatus() != observation.status()
                    || observation.status() == ChunkStatus.EMPTY || observation.status() == ChunkStatus.FULL)
                throw new ChunkProtocolException("C2ME generation target/status contract changed");
            ChunkMapLoadAccess map = (ChunkMapLoadAccess) (Object) observation.map();
            world = world(authority.requireChunkWorld(map.phoenium$level()), observation.map(), map.phoenium$poi());
        } catch (Throwable failure) {
            throw breach(failure);
        }

        final C2meGenerationGraph graph;
        final List<C2meGenerationGraph> stale = new ArrayList<>();
        Optional<ChunkCustodyModel.LifecycleId> replacedLifecycle;
        c2meGate.lock();
        try {
            C2meGenerationGraph existing = c2meSlots.get(observation.statusSlot());
            if (existing != null) {
                if (!existing.sameExactLineage(observation))
                    throw breach(new ChunkProtocolException("C2ME status-slot identity reused for different lineage"));
                return existing.returned;
            }
            for (C2meGenerationGraph candidate : c2meGenerationGraphs)
                if (candidate.sameTarget(observation) && candidate.observation.statusSlot() != observation.statusSlot())
                    stale.add(candidate);

            RoutingKey key = new RoutingKey(world.authority.world(), observation.position().x(), observation.position().z());
            replacedLifecycle = world.authority.services().lifecycles()
                    .resolveCurrentLifecycle(world.authority.worldInstanceSequence(), key);
            ChunkCustodyModel.LifecycleId lifecycle = world.authority.services().lifecycles()
                    .activateHolder(world.authority.worldInstanceSequence(), key, observation.centerHolder());
            if (replacedLifecycle.isPresent() && replacedLifecycle.orElseThrow().equals(lifecycle))
                replacedLifecycle = Optional.empty();
            long reservationSequence = c2meReservationSequence++;
            String statusName = observation.status().getName();
            ChunkCustodyModel.WorkId work = world.authority.services().works().beginOrResolveSharedWork(lifecycle,
                    new ChunkWorkRegistry.WorkReservation("generation-status", statusName, reservationSequence));
            world.authority.services().trackHolder(lifecycle, observation.centerHolder());
            ChunkCustodyModel.StageId stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.STATUS_PUBLISH,
                    statusName, 0);
            PendingVanillaStage<ChunkAccess, ChunkAccess> pending = new PendingVanillaStage<>(
                    world.authority.services().custody(), lifecycle, work, stage,
                    new dev.phoenium.task.TaskCorrelationId(reservationSequence), (ready, claim) -> ready);
            graph = new C2meGenerationGraph(world, observation, lifecycle, work, pending);
            pending.requireClaimFence(claim -> graph.claimFence(claim));
            c2meSlots.put(observation.statusSlot(), graph);
            c2meGenerationGraphs.add(graph);
            c2meRegistrations++;
        } finally { c2meGate.unlock(); }

        if (replacedLifecycle.isPresent()) supersedeLifecycle(replacedLifecycle.orElseThrow());
        for (C2meGenerationGraph prior : stale) prior.control(ChunkStageAdmission.Result.SUPERSEDED);
        if (graph.terminal()) return graph.returned;

        final CompletableFuture<ChunkAccess> producer;
        try {
            producer = Objects.requireNonNull(original.get(), "C2ME ChunkStep.apply returned null");
        } catch (RuntimeException | Error failure) {
            graph.synchronousProducerFailure();
            throw failure;
        }
        graph.attachProducer(producer);
        return graph.returned;
    }

    /**
     * C2ME's locked asynchronous DISK path constructs an unpublished ProtoChunk on a provider
     * worker.  That private construction does not acquire regional mutation authority; only the
     * exact ScalableLux persisted-light calls are admitted through the one-shot private scope.
     * C2ME's deferred POI queue is routed later through {@link RegionalServiceDispatcher}.
     */
    public net.minecraft.world.level.chunk.ProtoChunk c2meReadAsync(Object context, SerializableChunkData data,
            ServerLevel level, ChunkPos position,
            Supplier<net.minecraft.world.level.chunk.ProtoChunk> original) {
        Objects.requireNonNull(context); Objects.requireNonNull(data); Objects.requireNonNull(level);
        Objects.requireNonNull(position); Objects.requireNonNull(original);
        if (!open) throw new C2meProviderControlException(ChunkStageAdmission.Result.SHUTDOWN);
        if (!position.equals(data.chunkPos()))
            throw breach(new ChunkProtocolException("C2ME async DISK parsed chunk position changed"));
        if (!ScalableLuxLightBridge.ownsPersistedReload(level.getLightEngine()))
            throw new ChunkProtocolException("C2ME async private DISK read requires exact ScalableLux persisted-light ownership");

        // A retry can arrive after an upstream provider failure that prevented the final drain
        // mapper from running.  Retire that exact prior attempt before creating the next one.
        retirePriorC2meAsyncContext(context);

        final C2meReadAccess.Observation observation;
        final WorldBinding world;
        try {
            observation = C2meReadAccess.capture(context, position);
            ChunkMapLoadAccess map = (ChunkMapLoadAccess) (Object) observation.map();
            if (map.phoenium$level() != level)
                throw new ChunkProtocolException("C2ME async DISK read world identity changed");
            world = world(authority.requireChunkWorld(level), observation.map(), map.phoenium$poi());
        } catch (Throwable failure) {
            throw breach(failure);
        }

        RoutingKey key = new RoutingKey(world.authority.world(), position.x(), position.z());
        Optional<ChunkCustodyModel.LifecycleId> prior = world.authority.services().lifecycles()
                .resolveCurrentLifecycle(world.authority.worldInstanceSequence(), key);
        ChunkCustodyModel.LifecycleId lifecycle = world.authority.services().lifecycles()
                .activateHolder(world.authority.worldInstanceSequence(), key, observation.centerHolder());
        if (prior.isPresent() && !prior.orElseThrow().equals(lifecycle)) supersedeLifecycle(prior.orElseThrow());
        ChunkPrerequisiteServices services = world.authority.services();
        services.trackHolder(lifecycle, observation.centerHolder());

        final long reservation;
        c2meGate.lock();
        try { reservation = c2meReservationSequence++; }
        finally { c2meGate.unlock(); }
        ChunkCustodyModel.WorkId work = services.works().beginOrResolveSharedWork(lifecycle,
                new ChunkWorkRegistry.WorkReservation("c2me-provider", "DISK_ASYNC_PRIVATE", reservation));
        ChunkCustodyModel.StageId stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.PARSED_APPLY,
                "C2ME_DISK_ASYNC_PRIVATE", 0);
        LightInputService.BatchId batch = registerReloadLight(services, lifecycle, work, stage, data, level);
        if (batch == null) {
            services.works().retireWork(work, true);
            services.works().releaseRetiredWork(work);
            services.tryReleaseRetiredLifecycle(lifecycle);
            throw new C2meProviderControlException(open ? ChunkStageAdmission.Result.SUPERSEDED
                    : ChunkStageAdmission.Result.SHUTDOWN);
        }

        C2meAsyncReadGraph graph = new C2meAsyncReadGraph(world, observation, lifecycle, work, batch, data, level);
        boolean overlap;
        c2meGate.lock();
        try {
            overlap = c2meReadSlots.containsKey(observation.statusSlot()) || c2meReadContexts.containsKey(context);
            if (!overlap) {
                c2meReadSlots.put(observation.statusSlot(), graph);
                c2meReadContexts.put(context, graph);
                c2meReadGraphs.add(graph);
                c2meReadRegistrations++;
            }
        } finally { c2meGate.unlock(); }
        if (overlap) {
            graph.terminal(false);
            throw breach(new ChunkProtocolException("overlapping C2ME async DISK lineage"));
        }

        try (PrivateC2meReadInvocation ignored = new PrivateC2meReadInvocation(observation, services,
                lifecycle, work, position, data, batch, level)) {
            net.minecraft.world.level.chunk.ProtoChunk result = Objects.requireNonNull(original.get(),
                    "C2ME async SerializableChunkData.read returned null");
            graph.readReturned(result);
            return result;
        } catch (RuntimeException | Error failure) {
            graph.terminal(false);
            throw failure;
        }
    }

    /**
     * C2ME's synchronous fallback executes SerializableChunkData.read on the main thread.  POI
     * mutation is synchronous there, so this path deliberately retains the existing regional
     * claim/lease and {@link SynchronousChunkInvocation} contract.
     */
    public net.minecraft.world.level.chunk.ProtoChunk c2meRead(Object context, SerializableChunkData data,
                                                                ServerLevel level, ChunkPos position,
                                                                Supplier<net.minecraft.world.level.chunk.ProtoChunk> original) {
        Objects.requireNonNull(context); Objects.requireNonNull(data); Objects.requireNonNull(level);
        Objects.requireNonNull(position); Objects.requireNonNull(original);
        retirePriorC2meAsyncContext(context);
        if (!open) throw new C2meProviderControlException(ChunkStageAdmission.Result.SHUTDOWN);
        if (!position.equals(data.chunkPos()))
            throw breach(new ChunkProtocolException("C2ME DISK parsed chunk position changed"));

        final C2meReadAccess.Observation observation;
        final WorldBinding world;
        try {
            observation = C2meReadAccess.capture(context, position);
            ChunkMapLoadAccess map = (ChunkMapLoadAccess) (Object) observation.map();
            if (map.phoenium$level() != level)
                throw new ChunkProtocolException("C2ME DISK read world identity changed");
            world = world(authority.requireChunkWorld(level), observation.map(), map.phoenium$poi());
        } catch (Throwable failure) {
            throw breach(failure);
        }

        RoutingKey key = new RoutingKey(world.authority.world(), position.x(), position.z());
        Optional<ChunkCustodyModel.LifecycleId> prior = world.authority.services().lifecycles()
                .resolveCurrentLifecycle(world.authority.worldInstanceSequence(), key);
        ChunkCustodyModel.LifecycleId lifecycle = world.authority.services().lifecycles()
                .activateHolder(world.authority.worldInstanceSequence(), key, observation.centerHolder());
        if (prior.isPresent() && !prior.orElseThrow().equals(lifecycle)) supersedeLifecycle(prior.orElseThrow());
        ChunkPrerequisiteServices services = world.authority.services();
        services.trackHolder(lifecycle, observation.centerHolder());

        final long reservation;
        c2meGate.lock();
        try { reservation = c2meReservationSequence++; }
        finally { c2meGate.unlock(); }
        ChunkCustodyModel.WorkId work = services.works().beginOrResolveSharedWork(lifecycle,
                new ChunkWorkRegistry.WorkReservation("c2me-provider", "DISK", reservation));
        ChunkCustodyModel.StageId stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.PARSED_APPLY,
                "C2ME_DISK_READ", 0);
        LightInputService.BatchId batch = registerReloadLight(services, lifecycle, work, stage, data, level);
        if (batch == null) {
            if (Boolean.getBoolean("phoenium.test.diagnostics")) {
                var custody = services.custody();
                custody.gate().lock();
                try {
                    System.out.println("PHOENIUM_C2ME_READ_REGISTER_REJECT pos=" + position
                            + " open=" + open
                            + " admissionsOpen=" + custody.admissionsOpenLocked()
                            + " reserved=" + services.works().hasReservation(work)
                            + " metadataEntitled=" + custody.metadataEntitledLocked(work)
                            + " lifecycle=" + custody.lifecycleState(lifecycle).orElse(null)
                            + " work=" + custody.workState(work).orElse(null)
                            + " sameHolder=" + services.lifecycles().observeSameHolder(lifecycle, observation.centerHolder())
                            + " stillCurrent=" + observation.stillCurrent());
                } finally {
                    custody.gate().unlock();
                }
            }
            services.works().retireWork(work, true);
            services.works().releaseRetiredWork(work);
            services.tryReleaseRetiredLifecycle(lifecycle);
            throw new C2meProviderControlException(open ? ChunkStageAdmission.Result.SUPERSEDED
                    : ChunkStageAdmission.Result.SHUTDOWN);
        }

        PendingVanillaStage<SerializableChunkData, net.minecraft.world.level.chunk.ProtoChunk> pending =
                new PendingVanillaStage<>(services.custody(), lifecycle, work, stage,
                        new dev.phoenium.task.TaskCorrelationId(reservation), (parsed, claim) -> {
                    claim.require(stage);
                    try (SynchronousChunkInvocation ignored = new SynchronousChunkInvocation(claim, services,
                            position, parsed, batch, level.dimensionType().hasSkyLight())) {
                        return original.get();
                    }
                });
        pending.requireReloadLight(services.light(), batch);
        pending.requireClaimFence(claim -> {
            claim.require(stage);
            if (!open) return ChunkStageAdmission.Result.SHUTDOWN;
            if (!services.lifecycles().observeSameHolder(lifecycle, observation.centerHolder())
                    || !observation.stillCurrent()) return ChunkStageAdmission.Result.SUPERSEDED;
            return null;
        });

        boolean cancelled = false;
        boolean applied = false;
        try {
            net.minecraft.world.level.chunk.ProtoChunk result = executeProviderNow(world, pending, data);
            applied = true;
            return result;
        } catch (C2meProviderControlException control) {
            cancelled = true;
            throw control;
        } finally {
            services.light().forget(batch);
            if (applied) services.settleFinishedExecution(work, observation.centerHolder());
            else {
                services.works().retireWork(work, cancelled);
                services.works().releaseRetiredWork(work);
                services.tryReleaseRetiredLifecycle(lifecycle);
            }
        }
    }

    private LightInputService.BatchId registerReloadLight(ChunkPrerequisiteServices services,
                                                            ChunkCustodyModel.LifecycleId lifecycle,
                                                            ChunkCustodyModel.WorkId work,
                                                            ChunkCustodyModel.StageId stage,
                                                            SerializableChunkData data, ServerLevel level) {
        boolean sky = level.dimensionType().hasSkyLight();
        ArrayList<LightInputService.SectionInput> inputs = new ArrayList<>();
        long occurrence = 1;
        for (SerializableChunkData.SectionData section : data.sectionData()) {
            String sectionKey = Long.toString(net.minecraft.core.SectionPos.of(data.chunkPos(), section.y()).asLong());
            if (section.blockLight() != null)
                inputs.add(new LightInputService.SectionInput(LightInputService.Layer.BLOCK, sectionKey, occurrence++));
            if (sky && section.skyLight() != null)
                inputs.add(new LightInputService.SectionInput(LightInputService.Layer.SKY, sectionKey, occurrence++));
        }
        LightInputService.BatchId batch = new LightInputService.BatchId(lifecycle, work, stage,
                batchSequence.getAndIncrement());
        return services.light().register(batch, !inputs.isEmpty(), inputs) ? batch : null;
    }

    /** Rebinds only ReadFromDiskAsync's final post-observeOn map.  Phoenium owner-routes the
     * captured deferred queue, waits for that lease to release, and only then invokes the exact
     * provider mapper against the queue that its own audited drain has already consumed. */
    public Object c2meAsyncFinalMap(Object context, Object source, Object providerMapper) {
        Objects.requireNonNull(context); Objects.requireNonNull(source); Objects.requireNonNull(providerMapper);
        return C2meReadAccess.rebindAsyncFinalMap(source, providerMapper,
                pair -> c2meAsyncFinalPair(context, providerMapper, pair));
    }

    /** The returned Completable encloses C2ME retry/fallback, so this callback represents an
     * actual provider cancellation rather than an intermediate retry subscription ending. */
    public Object c2meAsyncOuterLifecycle(Object context, Object completable) {
        Objects.requireNonNull(context); Objects.requireNonNull(completable);
        return C2meReadAccess.onOuterDispose(completable, () -> c2meAsyncDisposed(context));
    }

    void c2meAsyncDisposed(Object context) {
        final C2meAsyncReadGraph graph;
        c2meGate.lock();
        try { graph = c2meReadContexts.get(context); }
        finally { c2meGate.unlock(); }
        if (graph != null) graph.control(ChunkStageAdmission.Result.CANCELLED);
    }

    private java.util.concurrent.CompletionStage<net.minecraft.world.level.chunk.ProtoChunk> c2meAsyncFinalPair(
            Object context, Object providerMapper, Object pair) {
        C2meReadAccess.ProviderPair carrier = C2meReadAccess.inspectProviderPair(pair);
        final C2meAsyncReadGraph graph;
        c2meGate.lock();
        try { graph = c2meReadResults.get(carrier.chunk()); }
        finally { c2meGate.unlock(); }
        if (graph == null)
            return c2meAsyncUnboundFinalPair(carrier, providerMapper);
        if (graph.observation.context() != context || graph.result != carrier.chunk())
            return CompletableFuture.failedFuture(new ChunkProtocolException("C2ME async final pair changed exact lineage"));
        return graph.routeFinal(carrier, providerMapper);
    }

    /**
     * An empty storage result never invokes SerializableChunkData.read, so it cannot create a
     * private-read graph or a light receipt.  C2ME still represents that result as the same
     * Chunk/ReferenceArrayList carrier and expects its ordinary final mapper to drain the (empty)
     * queue.  Preserve that provider-owned no-input branch, but reject any unbound pair that has
     * captured deferred work: a non-empty queue is evidence that a persisted-read lineage was
     * lost, not permission to run provider mutation without an admission.
     */
    private java.util.concurrent.CompletionStage<net.minecraft.world.level.chunk.ProtoChunk> c2meAsyncUnboundFinalPair(
            C2meReadAccess.ProviderPair carrier, Object providerMapper) {
        try {
            C2meReadAccess.requireEmptyProviderQueue(carrier);
            if (OwnershipContext.current().isPresent())
                throw new ChunkProtocolException("C2ME empty final pair resumed under regional ownership");
            Object mapped = C2meReadAccess.invokeProviderMapper(providerMapper, carrier.pair());
            if (OwnershipContext.current().isPresent())
                throw new ChunkProtocolException("C2ME empty final mapper retained regional ownership");
            if (mapped != carrier.chunk())
                throw new ChunkProtocolException("C2ME empty final mapper changed ProtoChunk identity");
            return CompletableFuture.completedFuture(carrier.chunk());
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void retirePriorC2meAsyncContext(Object context) {
        final C2meAsyncReadGraph prior;
        c2meGate.lock();
        try { prior = c2meReadContexts.get(context); }
        finally { c2meGate.unlock(); }
        if (prior == null || prior.terminal.get()) return;
        if (prior.drainSubmitted.get())
            throw breach(new ChunkProtocolException("C2ME async retry/fallback overlapped an owner-routed final drain"));
        prior.terminal(false);
    }

    /** Captures the exact provider status slot when C2ME builds its Rx task; no Phoenium graph is retained until it runs. */
    public Runnable c2meFullRunnable(Object context, Runnable original) {
        Objects.requireNonNull(original, "original");
        C2meFullAccess.Observation observation = C2meFullAccess.capture(context, C2meFullAccess.Kind.FULL);
        return () -> runC2meFull(observation, original);
    }

    /** Delayed full events are a second provider Runnable on the same exact SERVER_ACCESSIBLE slot. */
    public Runnable c2meDelayedFullEventsRunnable(Object context, Runnable original) {
        Objects.requireNonNull(original, "original");
        C2meFullAccess.Observation observation = C2meFullAccess.capture(context, C2meFullAccess.Kind.FULL);
        return () -> runC2meDelayedFullEvents(observation, original);
    }

    /** The notickvd-overwritten sending status has a distinct provider slot and therefore a distinct stage. */
    public Runnable c2meSendReadyRunnable(Object context, Runnable original) {
        Objects.requireNonNull(original, "original");
        C2meFullAccess.Observation observation = C2meFullAccess.capture(context, C2meFullAccess.Kind.SEND);
        return () -> runC2meSendReady(observation, original);
    }

    /** Rebinds the exact notickvd fromRunnable node while retaining its exact subscribeOn scheduler instance. */
    public Object c2meSendReadyGraph(Object context, Object providerGraph) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(providerGraph, "providerGraph");
        return C2meFullAccess.rebindSendReadyGraph(providerGraph, original -> c2meSendReadyRunnable(context, original));
    }

    private void runC2meFull(C2meFullAccess.Observation observation, Runnable original) {
        if (!open) throw new C2meProviderControlException(ChunkStageAdmission.Result.SHUTDOWN);
        if (!observation.stillCurrent()) throw new C2meProviderControlException(ChunkStageAdmission.Result.SUPERSEDED);
        ChunkAccess source = observation.requireProtoChunk();
        ChunkMapLoadAccess map = (ChunkMapLoadAccess) (Object) observation.map();
        WorldBinding world = world(authority.requireChunkWorld(map.phoenium$level()), observation.map(), map.phoenium$poi());
        C2meFullGraph graph;
        List<C2meFullGraph> stale = new ArrayList<>();
        Optional<ChunkCustodyModel.LifecycleId> replacedLifecycle;
        c2meGate.lock();
        try {
            C2meFullGraph existing = c2meFullSlots.get(observation.statusSlot());
            if (existing != null) {
                if (!existing.sameExactLineage(observation))
                    throw breach(new ChunkProtocolException("C2ME FULL status-slot identity reused for different lineage"));
                graph = existing;
                replacedLifecycle = Optional.empty();
            } else {
                for (C2meFullGraph candidate : c2meFullGraphs)
                    if (!candidate.terminal() && candidate.observation.sameTarget(observation)
                            && candidate.observation.statusSlot() != observation.statusSlot()) stale.add(candidate);
                RoutingKey key = new RoutingKey(world.authority.world(), observation.position().x(), observation.position().z());
                replacedLifecycle = world.authority.services().lifecycles()
                        .resolveCurrentLifecycle(world.authority.worldInstanceSequence(), key);
                ChunkCustodyModel.LifecycleId lifecycle = world.authority.services().lifecycles()
                        .activateHolder(world.authority.worldInstanceSequence(), key, observation.userDataHolder());
                if (replacedLifecycle.isPresent() && replacedLifecycle.orElseThrow().equals(lifecycle))
                    replacedLifecycle = Optional.empty();
                world.authority.services().trackHolder(lifecycle, observation.userDataHolder());
                long reservation = c2meReservationSequence++;
                ChunkCustodyModel.WorkId work = world.authority.services().works().beginOrResolveSharedWork(lifecycle,
                        new ChunkWorkRegistry.WorkReservation("c2me-provider", "SERVER_ACCESSIBLE", reservation));
                graph = new C2meFullGraph(world, observation, lifecycle, work);
                c2meFullSlots.put(observation.statusSlot(), graph);
                c2meFullGraphs.add(graph);
                c2meFullRegistrations++;
            }
        } finally { c2meGate.unlock(); }
        if (replacedLifecycle.isPresent()) supersedeLifecycle(replacedLifecycle.orElseThrow());
        for (C2meFullGraph old : stale) old.control(ChunkStageAdmission.Result.SUPERSEDED);
        graph.runInitial(original, source);
    }

    private void runC2meDelayedFullEvents(C2meFullAccess.Observation observation, Runnable original) {
        if (!open) throw new C2meProviderControlException(ChunkStageAdmission.Result.SHUTDOWN);
        C2meFullGraph graph;
        c2meGate.lock();
        try { graph = c2meFullSlots.get(observation.statusSlot()); }
        finally { c2meGate.unlock(); }
        if (graph == null || graph.terminal() || !graph.sameExactLineage(observation))
            throw new C2meProviderControlException(ChunkStageAdmission.Result.SUPERSEDED);
        graph.runDelayed(original);
    }

    private void runC2meSendReady(C2meFullAccess.Observation observation, Runnable original) {
        if (!open) throw new C2meProviderControlException(ChunkStageAdmission.Result.SHUTDOWN);
        if (!observation.stillCurrent()) throw new C2meProviderControlException(ChunkStageAdmission.Result.SUPERSEDED);
        ChunkMapLoadAccess map = (ChunkMapLoadAccess) (Object) observation.map();
        WorldBinding world = world(authority.requireChunkWorld(map.phoenium$level()), observation.map(), map.phoenium$poi());
        RoutingKey key = new RoutingKey(world.authority.world(), observation.position().x(), observation.position().z());
        ChunkCustodyModel.LifecycleId lifecycle = world.authority.services().lifecycles()
                .activateHolder(world.authority.worldInstanceSequence(), key, observation.userDataHolder());
        world.authority.services().trackHolder(lifecycle, observation.userDataHolder());
        long reservation = c2meReservationSequence++;
        ChunkCustodyModel.WorkId work = world.authority.services().works().beginOrResolveSharedWork(lifecycle,
                new ChunkWorkRegistry.WorkReservation("c2me-provider", "SERVER_ACCESSIBLE_CHUNK_SENDING", reservation));
        var stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.SEND_READY, "C2ME_SEND", 0);
        PendingVanillaStage<Runnable, Void> pending = new PendingVanillaStage<>(world.authority.services().custody(),
                lifecycle, work, stage, new dev.phoenium.task.TaskCorrelationId(work.workSequence()), (body, claim) -> {
            claim.require(stage);
            C2meFullAccess.FullState full = observation.requireFullState();
            observation.requirePublication(full);
            body.run();
            observation.requireFullState(full);
            observation.requirePublication(full);
            return null;
        });
        pending.requireClaimFence(claim -> {
            claim.require(stage);
            if (!open) return ChunkStageAdmission.Result.SHUTDOWN;
            if (!world.authority.services().lifecycles().observeSameHolder(lifecycle, observation.userDataHolder())
                    || !observation.stillCurrent()) return ChunkStageAdmission.Result.SUPERSEDED;
            return null;
        });
        boolean cancelled = false;
        try {
            executeProviderNow(world, pending, original);
            c2meSendApplications++;
        } catch (C2meProviderControlException control) {
            cancelled = true; throw control;
        } finally {
            world.authority.services().works().retireWork(work, cancelled);
            world.authority.services().works().releaseRetiredWork(work);
            world.authority.services().tryReleaseRetiredLifecycle(lifecycle);
        }
    }

    private <I, O> O executeProviderNow(WorldBinding world, PendingVanillaStage<I, O> pending, I input) {
        pending.prepareFullServices();
        if (!pending.bindInput(input)) throw new ChunkProtocolException("C2ME provider stage rejected its exact retained input");
        pending.publishReady();
        for (int attempt = 0; attempt < 8; attempt++) {
            pending.prepareFullServices();
            RegionDirectory.PendingChange change = world.authority.directory().pendingChange(pending.chunk()).orElse(null);
            if (change != null && !change.completed()) {
                pending.stageExited(); pending.control(ChunkStageAdmission.Result.NOT_READY);
                throw new C2meProviderControlException(ChunkStageAdmission.Result.NOT_READY);
            }
            RegionRoute route = world.authority.directory().resolve(pending.chunk()).orElse(null);
            if (route == null) {
                pending.stageExited(); pending.control(ChunkStageAdmission.Result.NOT_READY);
                throw new C2meProviderControlException(ChunkStageAdmission.Result.NOT_READY);
            }
            ChunkStageAdmission.Result result = world.executor.execute(pending, route.region(), route.generation());
            if (result == ChunkStageAdmission.Result.STALE_ROUTE) continue;
            if (result == ChunkStageAdmission.Result.NOT_READY) {
                pending.control(result);
                throw new C2meProviderControlException(result);
            }
            ChunkStageOutcome<O> outcome = pending.outcome().getNow(null);
            if (outcome instanceof ChunkStageOutcome.Applied<O> applied) return applied.value();
            if (outcome instanceof ChunkStageOutcome.Control<O> control) throw new C2meProviderControlException(control.result());
            if (outcome instanceof ChunkStageOutcome.VanillaFailure<O> vanilla) throw unchecked(vanilla.failure());
            if (outcome instanceof ChunkStageOutcome.BarrierFailure<O> barrier) throw unchecked(barrier.failure());
            throw new ChunkProtocolException("C2ME provider stage has no terminal outcome after " + result);
        }
        pending.stageExited(); pending.control(ChunkStageAdmission.Result.NOT_READY);
        throw new C2meProviderControlException(ChunkStageAdmission.Result.NOT_READY);
    }

    private static int c2meSavedOccurrenceSlots(ChunkAccess source) {
        FullSavedInput saved = null;
        if (source instanceof ImposterProtoChunk imposter
                && imposter.getWrapped() instanceof FullSavedInput.PostLoadAccess access
                && access.phoenium$postLoad() instanceof FullSavedInput.ReloadProcessor reload) saved = reload.input();
        else if (source instanceof FullSavedInput.Access access) saved = access.phoenium$savedInput();
        if (saved == null) return 0;
        // Fresh generation owns an empty, dormant saved-input carrier. Only an activated reload
        // lineage needs a detached occurrence snapshot before FULL service reservation.
        if (!saved.tracking()) return 0;
        FullSavedInput.Snapshot snapshot = saved.snapshot();
        if (snapshot == null)
            throw new ChunkProtocolException("C2ME FULL saved input has no immutable occurrence snapshot");
        return snapshot.entities().size();
    }

    /** SectionStorage's background iterator asks whether this exact Q is owned by a pending graph. */
    public boolean ownsPoiFuture(CompletableFuture<?> future) {
        indexGate.lock(); try { return poiIndex.containsKey(future); }
        finally { indexGate.unlock(); }
    }

    /** Runs at an owner-free Minecraft event-loop poll point; one call performs at most one stage. */
    public boolean pumpOne() {
        if (OwnershipContext.current().isPresent()) return false;
        worlds.values().forEach(world -> world.pump.retryTopology());
        Delivery delivery = deliveries.pollFirst();
        if (delivery == null) return false;
        WorldBinding binding = worlds.get(delivery.stage().world());
        if (binding == null || !open) { delivery.stage().control(ChunkStageAdmission.Result.SHUTDOWN); return true; }
        ChunkStageAdmission.Result result = binding.executor.execute(delivery.stage(), delivery.owner(), delivery.generation());
        if (Boolean.getBoolean("phoenium.test.chunkBindingTrace") && result != ChunkStageAdmission.Result.APPLIED)
            logger.info("PHOENIUM_CHUNK_BINDING ADMISSION stage={} result={} world={} chunk={},{} generation={} owner={}",
                    delivery.stage().stage(), result, delivery.stage().world().value(), delivery.stage().chunk().x(),
                    delivery.stage().chunk().z(), delivery.generation().value(), delivery.owner().value());
        if (result == ChunkStageAdmission.Result.STALE_ROUTE || result == ChunkStageAdmission.Result.NOT_READY) {
            if (binding.pump.deliverPublished(delivery.stage()) == ChunkStageOwnerGateway.Delivery.QUEUED) staleReroutes++;
        } else if (result == ChunkStageAdmission.Result.DUPLICATE) duplicateApplications++;
        return true;
    }

    public int pumpAvailable() {
        if (OwnershipContext.current().isPresent()) return 0;
        int count = 0;
        while (count < 1024 && pumpOne()) count++;
        return count;
    }

    public void shutdown() {
        if (!open) return;
        open = false;
        var failures = new FullInputNotifications.Failures();
        for (var world : java.util.List.copyOf(worlds.values())) failures.run(world.pump::shutdown);
        List<ChunkLoadGraph> graphs = graphs();
        for (var graph : graphs) failures.run(graph::shutdown);
        for (var graph : c2meGraphs()) failures.run(() -> graph.control(ChunkStageAdmission.Result.SHUTDOWN));
        for (var graph : c2meFullGraphs()) failures.run(() -> graph.control(ChunkStageAdmission.Result.SHUTDOWN));
        for (var graph : c2meReadGraphs()) failures.run(() -> graph.control(ChunkStageAdmission.Result.SHUTDOWN));
        for (var world : java.util.List.copyOf(worlds.values())) failures.run(world.authority.services()::stopResidentHolders);
        Delivery delivery;
        while ((delivery = deliveries.pollFirst()) != null) {
            Delivery terminal = delivery;
            failures.run(() -> terminal.stage().control(ChunkStageAdmission.Result.SHUTDOWN));
        }
        if (Boolean.getBoolean("phoenium.test.chunkBindingTrace"))
            failures.run(() -> logger.info("PHOENIUM_CHUNK_BINDING STOPPED {}", diagnostics()));
        boolean noC2meGraphs = c2meRetainedGraphs() == 0;
        indexGate.lock(); try {
            if (holderGraphs.isEmpty() && returnedIndex.isEmpty() && poiIndex.isEmpty() && noC2meGraphs)
                worlds.clear();
        } finally { indexGate.unlock(); }
        failures.rethrow();
    }

    public int retainedGraphs() { indexGate.lock(); try { return holderGraphs.values().stream().mapToInt(Set::size).sum(); } finally { indexGate.unlock(); } }
    public int returnedIndexEntries() { indexGate.lock(); try { return returnedIndex.size(); } finally { indexGate.unlock(); } }
    public int poiIndexEntries() { indexGate.lock(); try { return poiIndex.size(); } finally { indexGate.unlock(); } }
    public int c2meRetainedGraphs() {
        c2meGate.lock(); try { return c2meGenerationGraphs.size() + c2meFullGraphs.size() + c2meReadGraphs.size(); }
        finally { c2meGate.unlock(); }
    }
    public String diagnostics() {
        ServiceCounts service = serviceCounts();
        return "registrations=" + registrations + " exactCaptures=" + exactCaptures + " poiInstalls=" + poiInstalls
                + " parsedApplications=" + parsedApplications + " recoveryApplications=" + recoveryApplications
                + " publications=" + publications + " privateUnloads=" + privateUnloads + " staleReroutes=" + staleReroutes
                + " duplicateApplications=" + duplicateApplications + " barriers=" + barriers
                + " retainedGraphs=" + retainedGraphs() + " returnedIndex=" + returnedIndexEntries()
                + " poiIndex=" + poiIndexEntries() + " c2meRegistrations=" + c2meRegistrations
                + " c2meApplications=" + c2meApplications + " c2meControls=" + c2meControls
                + " c2meProducerFailures=" + c2meProducerFailures + " c2meFullRegistrations=" + c2meFullRegistrations
                + " c2meFullApplications=" + c2meFullApplications + " c2meFullPublications=" + c2meFullPublications
                + " c2meSendApplications=" + c2meSendApplications + " c2meReadRegistrations=" + c2meReadRegistrations
                + " c2meReadApplications=" + c2meReadApplications + " c2meReadProviderFailures=" + c2meReadProviderFailures
                + " c2meRetained=" + c2meRetainedGraphs()
                + " synchronousInvocations=" + SynchronousChunkInvocation.retainedEntries()
                + " privateC2meReads=" + PrivateC2meReadInvocation.retainedEntries()
                + " lightBatches=" + service.lightBatches() + " lightOccurrences=" + service.lightOccurrences()
                + " lightReceipts=" + service.lightReceipts() + " poiEntries=" + service.poiEntries()
                + " metadataEntries=" + service.metadataEntries() + " lifecycleEntries=" + service.lifecycleEntries()
                + " workEntries=" + service.workEntries() + " custodyEntries=" + service.custodyEntries();
    }

    private ServiceCounts serviceCounts() {
        long lightBatches = 0, lightOccurrences = 0, lightReceipts = 0, poiEntries = 0;
        long metadataEntries = 0, lifecycleEntries = 0, workEntries = 0, custodyEntries = 0;
        for (WorldBinding binding : worlds.values()) {
            ChunkPrerequisiteServices services = binding.authority.services();
            lightBatches += services.light().entries();
            lightOccurrences += services.light().occurrenceEntries();
            lightReceipts += services.light().receiptEntries();
            poiEntries += services.poi().entries();
            metadataEntries += services.metadata().entries();
            lifecycleEntries += services.lifecycles().retainedEntries();
            workEntries += services.works().retainedEntries();
            custodyEntries += services.custody().retainedEntries();
        }
        return new ServiceCounts(lightBatches, lightOccurrences, lightReceipts, poiEntries, metadataEntries,
                lifecycleEntries, workEntries, custodyEntries);
    }

    private WorldBinding world(WorldAuthority world, ChunkMap map, PoiManager poi) {
        indexGate.lock();
        try {
            WorldBinding existing = worlds.get(world.world());
            if (existing != null) {
                if (existing.map != map || existing.poi != poi || existing.authority.services() != world.services())
                    throw new ChunkProtocolException("world load binding changed instance");
                return existing;
            }
            ChunkStageAdmission admission = new ChunkStageAdmission(world.services().custody(), world.directory(),
                    world.leases(), world.barrier());
            WorldBinding created = new WorldBinding(world, map, poi, new ChunkStageExecutor(admission));
            worlds.put(world.world(), created);
            return created;
        } finally { indexGate.unlock(); }
    }

    private final class WorldBinding {
        final WorldAuthority authority;
        final ChunkMap map;
        final PoiManager poi;
        final ChunkStageExecutor executor;
        final ChunkStageDeliveryPump pump;
        WorldBinding(WorldAuthority authority, ChunkMap map, PoiManager poi, ChunkStageExecutor executor) {
            this.authority = authority; this.map = map; this.poi = poi; this.executor = executor;
            pump = new ChunkStageDeliveryPump(authority.directory(), (route, stage) -> {
                if (!open) return ChunkStageOwnerGateway.Delivery.REJECTED_SHUTDOWN;
                if (!route.region().world().equals(stage.world())) return ChunkStageOwnerGateway.Delivery.REJECTED_ROUTE;
                deliveries.addLast(new Delivery(route.region(), route.generation(), stage));
                return ChunkStageOwnerGateway.Delivery.QUEUED;
            }, executor);
        }
    }

    /** One exact locked-provider async DISK attempt.  This is provider-lineage retention only;
     * regional ownership remains exclusively in RegionScheduler/RegionLeaseManager. */
    private final class C2meAsyncReadGraph {
        final WorldBinding world;
        final C2meReadAccess.Observation observation;
        final ChunkCustodyModel.LifecycleId lifecycle;
        final ChunkCustodyModel.WorkId work;
        final LightInputService.BatchId batch;
        final SerializableChunkData parsed;
        final ServerLevel level;
        final AtomicBoolean drainSubmitted = new AtomicBoolean();
        final AtomicBoolean terminal = new AtomicBoolean();
        final AtomicReference<ChunkStageAdmission.Result> requestedControl = new AtomicReference<>();
        volatile net.minecraft.world.level.chunk.ProtoChunk result;

        C2meAsyncReadGraph(WorldBinding world, C2meReadAccess.Observation observation,
                           ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work,
                           LightInputService.BatchId batch, SerializableChunkData parsed, ServerLevel level) {
            this.world = Objects.requireNonNull(world); this.observation = Objects.requireNonNull(observation);
            this.lifecycle = Objects.requireNonNull(lifecycle); this.work = Objects.requireNonNull(work);
            this.batch = Objects.requireNonNull(batch); this.parsed = Objects.requireNonNull(parsed);
            this.level = Objects.requireNonNull(level);
        }

        void readReturned(net.minecraft.world.level.chunk.ProtoChunk value) {
            if (terminal.get() || result != null) throw new ChunkProtocolException("C2ME async read returned more than once");
            if (!observation.position().equals(value.getPos()))
                throw new ChunkProtocolException("C2ME async read returned a different chunk position");
            if (!observation.stillCurrent()
                    || !world.authority.services().lifecycles().observeSameHolder(lifecycle, observation.centerHolder())
                    || !world.authority.services().works().isCurrentWork(work))
                throw new C2meProviderControlException(ChunkStageAdmission.Result.SUPERSEDED);
            LightInputReceipt receipt = world.authority.services().light().receipt(batch).orElseThrow(
                    () -> new ChunkProtocolException("C2ME async read returned without its light receipt"));
            if (!world.authority.services().light().validates(receipt, batch))
                throw new ChunkProtocolException("C2ME async read returned with stale light receipt");
            result = value;
            c2meGate.lock();
            try {
                C2meAsyncReadGraph existing = c2meReadResults.put(value, this);
                if (existing != null && existing != this) {
                    c2meReadResults.put(value, existing);
                    throw new ChunkProtocolException("C2ME async ProtoChunk identity reused across DISK attempts");
                }
            } finally { c2meGate.unlock(); }
        }

        java.util.concurrent.CompletionStage<net.minecraft.world.level.chunk.ProtoChunk> routeFinal(
                C2meReadAccess.ProviderPair carrier, Object providerMapper) {
            if (terminal.get()) return CompletableFuture.failedFuture(
                    new C2meProviderControlException(ChunkStageAdmission.Result.SUPERSEDED));
            if (!drainSubmitted.compareAndSet(false, true))
                return CompletableFuture.failedFuture(new ChunkProtocolException("C2ME async final drain was submitted twice"));
            ChunkStageAdmission.Result preflight = controlReason();
            if (preflight != null) {
                Throwable failure = new C2meProviderControlException(preflight);
                failure = finish(false, failure);
                return CompletableFuture.failedFuture(failure);
            }

            final int deferredTasks;
            try { deferredTasks = C2meReadAccess.providerQueueSize(carrier); }
            catch (Throwable failure) {
                finish(false, failure);
                return CompletableFuture.failedFuture(failure);
            }

            // No captured authoritative work means there is no reason to take a regional mutation
            // lease.  The original mapper still executes at its audited post-observeOn position.
            if (deferredTasks == 0) return finishProviderContinuation(carrier, providerMapper, null, false);

            AtomicReference<Throwable> controlFailure = new AtomicReference<>();
            AtomicBoolean drainAttempted = new AtomicBoolean();
            CompletableFuture<net.minecraft.world.level.chunk.ProtoChunk> continued = new CompletableFuture<>();
            final RegionalServiceDispatcher.Handle handle;
            try {
                handle = authority.regionalDispatcher().submitC2mePersistedPoiAfterRelease(
                        lifecycle.chunkKey(), execution -> {
                    ChunkStageAdmission.Result control = controlReason();
                    if (control != null) {
                        controlFailure.compareAndSet(null, new C2meProviderControlException(control));
                        return;
                    }
                    requireLightReceipt();
                    drainAttempted.set(true);
                    // The locked provider's exact drainQueue is the authoritative body.  It owns
                    // queue order, continue-after-failure semantics, aggregation and queue clear.
                    // Crucially, the provider's Rx final mapper is NOT invoked under this lease.
                    C2meReadAccess.invokeProviderDrain(carrier);
                });
            } catch (Throwable failure) {
                finish(false, failure);
                return CompletableFuture.failedFuture(failure);
            }

            handle.completion().whenComplete((ignored, ownerFailure) -> {
                Throwable failure = controlFailure.get();
                Throwable owner = unwrapCompletion(ownerFailure);
                if (failure == null && owner == null) {
                    ChunkStageAdmission.Result control = controlReason();
                    if (control != null) failure = new C2meProviderControlException(control);
                }
                if (failure == null && !drainAttempted.get()) {
                    failure = owner != null ? owner
                            : new ChunkProtocolException("C2ME persisted POI owner completed without draining the provider queue");
                }

                // drainQueue clears the provider queue on both success and aggregated failure.
                // Only after the purpose-specific handle has observed lease release may the
                // provider callback resume.  On a drain failure we still run its now-empty final
                // mapper so its own ThreadState begin/close semantics are retained; the original
                // owner failure remains primary.
                if (failure == null && drainAttempted.get()) {
                    var stage = finishProviderContinuation(carrier, providerMapper, owner, true);
                    stage.whenComplete((value, continuationFailure) -> {
                        Throwable problem = unwrapCompletion(continuationFailure);
                        if (problem == null) continued.complete(value);
                        else continued.completeExceptionally(problem);
                    });
                    return;
                }

                if (failure == null) failure = owner;
                Throwable terminalFailure = finish(false, failure);
                if (terminalFailure != null) failure = terminalFailure;
                if (!(failure instanceof C2meProviderControlException)) c2meReadProviderFailures++;
                continued.completeExceptionally(failure);
            });
            return continued;
        }

        private java.util.concurrent.CompletionStage<net.minecraft.world.level.chunk.ProtoChunk> finishProviderContinuation(
                C2meReadAccess.ProviderPair carrier, Object providerMapper, Throwable ownerFailure,
                boolean requireConsumedQueue) {
            Throwable failure = ownerFailure;
            if (failure == null) {
                ChunkStageAdmission.Result control = controlReason();
                if (control != null) failure = new C2meProviderControlException(control);
            }
            if (failure == null || ownerFailure != null) {
                try { requireLightReceipt(); }
                catch (Throwable problem) {
                    if (failure == null) failure = problem;
                    else if (failure != problem) failure.addSuppressed(problem);
                }
            }
            if (requireConsumedQueue) {
                try {
                    if (C2meReadAccess.providerQueueSize(carrier) != 0)
                        throw new ChunkProtocolException("C2ME provider queue survived its owner-routed drain");
                } catch (Throwable problem) {
                    if (failure == null) failure = problem;
                    else if (failure != problem) failure.addSuppressed(problem);
                }
            }

            // A requested cancellation/shutdown/supersession after owner mutation but before
            // release must retire the attempt without resuming the provider continuation.
            ChunkStageAdmission.Result postOwnerControl = controlReason();
            if (postOwnerControl != null) {
                Throwable control = new C2meProviderControlException(postOwnerControl);
                if (failure == null) failure = control;
                else if (!(failure instanceof C2meProviderControlException)) failure.addSuppressed(control);
            }

            Object mapped = null;
            Throwable mapperFailure = null;
            if (!(failure instanceof C2meProviderControlException)) {
                try {
                    if (OwnershipContext.current().isPresent())
                        throw new ChunkProtocolException("C2ME provider continuation resumed under regional ownership");
                    mapped = C2meReadAccess.invokeProviderMapper(providerMapper, carrier.pair());
                    if (OwnershipContext.current().isPresent())
                        throw new ChunkProtocolException("C2ME provider continuation retained regional ownership");
                } catch (Throwable problem) { mapperFailure = problem; }
                if (mapperFailure != null) {
                    if (failure == null) failure = mapperFailure;
                    else if (failure != mapperFailure) failure.addSuppressed(mapperFailure);
                }
                if (mapperFailure == null && mapped != result) {
                    Throwable changed = new ChunkProtocolException("C2ME async final mapper changed ProtoChunk identity");
                    if (failure == null) failure = changed;
                    else failure.addSuppressed(changed);
                }
            }

            if (failure == null) {
                ChunkStageAdmission.Result lateControl = controlReason();
                if (lateControl != null) failure = new C2meProviderControlException(lateControl);
            }

            Throwable terminalFailure = finish(failure == null, failure);
            if (terminalFailure != null) failure = terminalFailure;
            if (failure == null) {
                c2meReadApplications++;
                return CompletableFuture.completedFuture(result);
            }
            if (!(failure instanceof C2meProviderControlException)) c2meReadProviderFailures++;
            return CompletableFuture.failedFuture(failure);
        }

        private ChunkStageAdmission.Result controlReason() {
            ChunkStageAdmission.Result requested = requestedControl.get();
            if (requested != null) return requested;
            if (!open) return ChunkStageAdmission.Result.SHUTDOWN;
            ChunkPrerequisiteServices services = world.authority.services();
            if (!services.lifecycles().observeSameHolder(lifecycle, observation.centerHolder())
                    || !services.works().isCurrentWork(work) || !observation.stillCurrent())
                return ChunkStageAdmission.Result.SUPERSEDED;
            return null;
        }

        void control(ChunkStageAdmission.Result reason) {
            if (reason != ChunkStageAdmission.Result.CANCELLED && reason != ChunkStageAdmission.Result.SUPERSEDED
                    && reason != ChunkStageAdmission.Result.SHUTDOWN)
                throw new IllegalArgumentException("unsupported C2ME read control " + reason);
            requestedControl.compareAndSet(null, reason);
            if (!drainSubmitted.get()) terminal(false);
        }

        private void requireLightReceipt() {
            LightInputReceipt receipt = world.authority.services().light().receipt(batch).orElseThrow(
                    () -> new ChunkProtocolException("C2ME async final drain lost its light receipt"));
            if (!world.authority.services().light().validates(receipt, batch))
                throw new ChunkProtocolException("C2ME async final drain has stale light receipt");
        }

        /** Returns the effective failure after cleanup, preserving the provider failure as primary
         * while still tripping the shared barrier for cleanup invariant failures. */
        private Throwable finish(boolean success, Throwable primary) {
            Throwable cleanup = null;
            try { terminal(success); }
            catch (Throwable failure) { cleanup = failure; }
            if (cleanup != null) {
                authority.chunkBarrier(cleanup);
                if (primary != null && primary != cleanup) primary.addSuppressed(cleanup);
                else primary = cleanup;
            }
            return primary;
        }

        void terminal(boolean success) {
            if (!terminal.compareAndSet(false, true)) return;
            c2meGate.lock();
            try {
                c2meReadSlots.remove(observation.statusSlot(), this);
                c2meReadContexts.remove(observation.context(), this);
                if (result != null) c2meReadResults.remove(result, this);
                c2meReadGraphs.remove(this);
            } finally { c2meGate.unlock(); }

            ChunkPrerequisiteServices services = world.authority.services();
            var failures = new FullInputNotifications.Failures();
            failures.run(() -> services.light().forget(batch));
            if (success) failures.run(() -> services.settleFinishedExecution(work, observation.centerHolder()));
            else {
                failures.run(() -> services.works().retireWork(work, true));
                failures.run(() -> services.works().releaseRetiredWork(work));
                failures.run(() -> services.tryReleaseRetiredLifecycle(lifecycle));
            }
            failures.rethrow();
        }
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        if (failure instanceof CompletionException completion && completion.getCause() != null) return completion.getCause();
        return failure;
    }

    private final class C2meGenerationGraph {
        final WorldBinding world;
        final C2meGenerationAccess.Observation observation;
        final ChunkCustodyModel.LifecycleId lifecycle;
        final ChunkCustodyModel.WorkId work;
        final PendingVanillaStage<ChunkAccess, ChunkAccess> pending;
        final ChunkStageRegistration<ChunkAccess, ChunkAccess> registration;
        final CompletableFuture<ChunkAccess> returned = new CompletableFuture<>();
        final ProducerRelay relay = new ProducerRelay(this);
        private final AtomicBoolean terminal = new AtomicBoolean();

        C2meGenerationGraph(WorldBinding world, C2meGenerationAccess.Observation observation,
                            ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work,
                            PendingVanillaStage<ChunkAccess, ChunkAccess> pending) {
            this.world = world; this.observation = observation; this.lifecycle = lifecycle;
            this.work = work; this.pending = pending;
            this.registration = new ChunkStageRegistration<>(pending, world.pump);
            pending.outcome().whenComplete(this::stageCompleted);
        }

        boolean sameExactLineage(C2meGenerationAccess.Observation other) {
            return observation.statusSlot() == other.statusSlot()
                    && observation.backingHolder() == other.backingHolder()
                    && observation.centerHolder() == other.centerHolder()
                    && observation.map() == other.map()
                    && observation.status() == other.status()
                    && observation.position().equals(other.position());
        }
        boolean sameTarget(C2meGenerationAccess.Observation other) {
            return observation.map() == other.map() && observation.status() == other.status()
                    && observation.position().equals(other.position());
        }
        boolean terminal() { return terminal.get(); }

        ChunkStageAdmission.Result claimFence(ChunkStageClaim claim) {
            claim.require(pending.stage());
            if (!open) return ChunkStageAdmission.Result.SHUTDOWN;
            if (!world.authority.services().lifecycles().observeSameHolder(lifecycle, observation.centerHolder()))
                return ChunkStageAdmission.Result.SUPERSEDED;
            return observation.stillCurrent() ? null : ChunkStageAdmission.Result.SUPERSEDED;
        }

        void attachProducer(CompletableFuture<ChunkAccess> producer) {
            Objects.requireNonNull(producer);
            if (terminal()) return;
            producer.whenComplete(relay::completed);
            if (terminal()) relay.detach(this);
        }

        void producerCompleted(ChunkAccess value, Throwable failure) {
            if (failure != null) { producerFailure(failure); return; }
            if (terminal()) return;
            try { registration.callback().apply(value); }
            catch (Throwable bridgeFailure) { adapterFailure(bridgeFailure); }
        }

        void synchronousProducerFailure() {
            if (!beginTerminal()) return;
            pending.discardUnused();
            unbindC2me(this);
            settle(false);
        }

        void producerFailure(Throwable failure) {
            if (!beginTerminal()) return;
            pending.discardUnused();
            unbindC2me(this);
            settle(false);
            c2meProducerFailures++;
            returned.completeExceptionally(failure);
        }

        void adapterFailure(Throwable failure) {
            if (!beginTerminal()) return;
            pending.discardUnused();
            unbindC2me(this);
            settle(false);
            returned.completeExceptionally(failure);
            breach(failure);
        }

        void control(ChunkStageAdmission.Result reason) {
            if (!terminal()) pending.control(reason);
        }

        private void stageCompleted(ChunkStageOutcome<ChunkAccess> outcome, Throwable failure) {
            if (failure != null) { adapterFailure(failure); return; }
            if (!beginTerminal()) return;
            unbindC2me(this);
            if (outcome instanceof ChunkStageOutcome.Applied<ChunkAccess> applied) {
                settle(false); c2meApplications++; returned.complete(applied.value());
            } else if (outcome instanceof ChunkStageOutcome.Control<ChunkAccess> control) {
                settle(true); c2meControls++;
                returned.completeExceptionally(new C2meGenerationControlException(control.result()));
            } else if (outcome instanceof ChunkStageOutcome.VanillaFailure<ChunkAccess> vanilla) {
                settle(false); returned.completeExceptionally(vanilla.failure());
            } else if (outcome instanceof ChunkStageOutcome.BarrierFailure<ChunkAccess> barrier) {
                settle(false); returned.completeExceptionally(barrier.failure());
            } else {
                Throwable invalid = new ChunkProtocolException("unknown C2ME generation outcome " + outcome);
                settle(false); returned.completeExceptionally(invalid); breach(invalid);
            }
        }

        private boolean beginTerminal() {
            if (!terminal.compareAndSet(false, true)) return false;
            relay.detach(this);
            return true;
        }

        private void settle(boolean cancelled) {
            ChunkPrerequisiteServices services = world.authority.services();
            services.works().retireWork(work, cancelled);
            services.works().releaseRetiredWork(work);
            services.tryReleaseRetiredLifecycle(lifecycle);
        }
    }

    private final class C2meFullGraph {
        final WorldBinding world;
        final C2meFullAccess.Observation observation;
        final ChunkCustodyModel.LifecycleId lifecycle;
        final ChunkCustodyModel.WorkId work;
        private final AtomicBoolean initialStarted = new AtomicBoolean();
        private final AtomicBoolean delayedStarted = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile C2meFullAccess.FullState fullState;
        private volatile boolean delayed;

        C2meFullGraph(WorldBinding world, C2meFullAccess.Observation observation,
                      ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work) {
            this.world = world; this.observation = observation; this.lifecycle = lifecycle; this.work = work;
        }

        boolean sameExactLineage(C2meFullAccess.Observation other) { return observation.sameExactLineage(other); }
        boolean terminal() { return terminal.get(); }

        void runInitial(Runnable original, ChunkAccess source) {
            if (!initialStarted.compareAndSet(false, true)) throw new ChunkProtocolException("repeated C2ME FULL provider Runnable");
            delayed = observation.delayFullEvents();
            try {
                var state = new AtomicReference<C2meFullAccess.FullState>();
                var stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.FULL_FINISH, "C2ME_SERVER_ACCESSIBLE", 0);
                var services = new C2meFullServiceBinding(world, stage, observation.userDataHolder(), source);
                PendingVanillaStage<Runnable, Void> pending = new PendingVanillaStage<>(world.authority.services().custody(),
                        lifecycle, work, stage, new dev.phoenium.task.TaskCorrelationId(work.workSequence()), (body, claim) -> {
                    claim.require(stage);
                    try (var ignored = services.enter(claim)) {
                        body.run();
                        state.set(observation.requireFullState());
                    }
                    return null;
                });
                pending.requireProviderFull(services);
                pending.requireClaimFence(claim -> fullClaimFence(claim, stage, null));
                executeProviderNow(world, pending, original);
                fullState = Objects.requireNonNull(state.get(), "C2ME FULL state receipt");
                c2meFullApplications++;
                if (!delayed) {
                    publish();
                    finish(false);
                }
            } catch (Throwable failure) {
                finish(failure instanceof C2meProviderControlException);
                throw unchecked(failure);
            }
        }

        void runDelayed(Runnable original) {
            if (!delayed || fullState == null) throw new ChunkProtocolException("C2ME delayed FULL events have no retained FULL state");
            if (!delayedStarted.compareAndSet(false, true)) throw new ChunkProtocolException("repeated C2ME delayed FULL provider Runnable");
            try {
                var stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.FULL_FINISH, "C2ME_FULL_EVENTS", 1);
                var services = new C2meFullServiceBinding(world, stage, observation.userDataHolder(), fullState.protoChunk());
                PendingVanillaStage<Runnable, Void> pending = new PendingVanillaStage<>(world.authority.services().custody(),
                        lifecycle, work, stage, new dev.phoenium.task.TaskCorrelationId(work.workSequence()), (body, claim) -> {
                    claim.require(stage);
                    observation.requireFullState(fullState);
                    try (var ignored = services.enter(claim)) { body.run(); }
                    observation.requireFullState(fullState);
                    return null;
                });
                pending.requireProviderFull(services);
                pending.requireClaimFence(claim -> fullClaimFence(claim, stage, fullState));
                executeProviderNow(world, pending, original);
                publish();
                finish(false);
            } catch (Throwable failure) {
                finish(failure instanceof C2meProviderControlException);
                throw unchecked(failure);
            }
        }

        void publish() {
            var stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.STATUS_PUBLISH, "C2ME_FULL", 2);
            PendingVanillaStage<C2meFullAccess.FullState, Void> pending = new PendingVanillaStage<>(
                    world.authority.services().custody(), lifecycle, work, stage,
                    new dev.phoenium.task.TaskCorrelationId(work.workSequence()), (state, claim) -> {
                claim.require(stage); observation.requirePublication(state); return null;
            });
            pending.requireClaimFence(claim -> fullClaimFence(claim, stage, fullState));
            executeProviderNow(world, pending, fullState);
            c2meFullPublications++;
        }

        ChunkStageAdmission.Result fullClaimFence(ChunkStageClaim claim, ChunkCustodyModel.StageId stage,
                                                  C2meFullAccess.FullState expected) {
            claim.require(stage);
            if (!open) return ChunkStageAdmission.Result.SHUTDOWN;
            if (!world.authority.services().lifecycles().observeSameHolder(lifecycle, observation.userDataHolder()))
                return ChunkStageAdmission.Result.SUPERSEDED;
            if (!observation.stillCurrent()) return ChunkStageAdmission.Result.SUPERSEDED;
            if (expected != null) observation.requireFullState(expected);
            return null;
        }

        void control(ChunkStageAdmission.Result reason) { finish(reason != ChunkStageAdmission.Result.SUPERSEDED); }

        void finish(boolean cancelled) {
            if (!terminal.compareAndSet(false, true)) return;
            unbindC2meFull(this);
            ChunkPrerequisiteServices services = world.authority.services();
            services.works().retireWork(work, cancelled);
            services.works().releaseRetiredWork(work);
            services.tryReleaseRetiredLifecycle(lifecycle);
        }
    }

    private final class C2meFullServiceBinding implements ProviderFullBinding {
        final WorldBinding world;
        final ChunkCustodyModel.StageId stage;
        final GenerationChunkHolder holder;
        final ChunkAccess source;
        private FullFinishingServices.Reservation reservation;

        C2meFullServiceBinding(WorldBinding world, ChunkCustodyModel.StageId stage,
                               GenerationChunkHolder holder, ChunkAccess source) {
            this.world = world; this.stage = stage; this.holder = holder; this.source = source;
        }

        @Override public LightInputService.Readiness readinessLocked(ChunkStageKind kind) {
            if (kind != ChunkStageKind.FULL_FINISH) return LightInputService.Readiness.FAILED;
            return reservation != null && reservation.valid(stage, holder)
                    ? LightInputService.Readiness.REGISTERED : LightInputService.Readiness.NOT_REGISTERED;
        }

        @Override public void prepare(ChunkStageKind kind) {
            if (kind != ChunkStageKind.FULL_FINISH || reservation != null) return;
            ServerLevel level = ((ChunkMapLoadAccess) (Object) world.map).phoenium$level();
            var server = level.getServer();
            if (server == null) throw new ChunkProtocolException("C2ME FULL world has no server");
            ArrayList<ServerLevel> levels = new ArrayList<>();
            for (ServerLevel candidate : server.getAllLevels()) levels.add(candidate);
            if (levels.stream().noneMatch(candidate -> candidate == level)) levels.add(level);
            reservation = FullFinishingServices.forServer(server).tryReserve(stage, holder, levels,
                    c2meSavedOccurrenceSlots(source));
        }

        @Override public boolean reserveLocked(ChunkStageKind kind) {
            return readinessLocked(kind) == LightInputService.Readiness.REGISTERED;
        }
        @Override public void unreserveLocked(ChunkStageKind kind) { }
        FullFinishingServices.Scope enter(ChunkStageClaim claim) {
            if (reservation == null) throw new ChunkProtocolException("C2ME FULL has no service reservation");
            return reservation.enter(claim);
        }
        @Override public void stageExited(ChunkStageKind kind) {
            FullFinishingServices.Reservation current = reservation;
            reservation = null;
            if (current != null) current.close();
        }
    }

    /** A provider future may outlive a superseded branch; detaching prevents replay and retention. */
    private final class ProducerRelay {
        private final AtomicReference<C2meGenerationGraph> graph;
        ProducerRelay(C2meGenerationGraph graph) { this.graph = new AtomicReference<>(graph); }
        void completed(ChunkAccess value, Throwable failure) {
            C2meGenerationGraph current = graph.getAndSet(null);
            if (current != null) current.producerCompleted(value, failure);
        }
        void detach(C2meGenerationGraph expected) { graph.compareAndSet(expected, null); }
    }

    private final class LoadEnvironment implements ChunkLoadGraph.Environment {
        private final WorldBinding world;
        private final ChunkMapLoadAccess map;
        private final ChunkPos position;
        private final ChunkStep step;
        private final ReservationEvidence reservation;
        LoadEnvironment(WorldBinding world, ChunkMapLoadAccess map, ChunkPos position,
                        ChunkStep step, ReservationEvidence reservation) {
            this.world = world; this.map = map; this.position = position; this.step = step; this.reservation = reservation;
        }
        @Override public ChunkPrerequisiteServices services() { return world.authority.services(); }
        @Override public void enqueue(PendingVanillaStage<?, ?> pending) {
            world.pump.publishAndDeliver(pending);
        }
        @Override public void requireHolder(ChunkLoadGraph.Token token) {
            if (token.reservation() != reservation || token.holder().getPos() != position
                    || !services().lifecycles().observeSameHolder(token.lifecycle(), token.holder())
                    || reservation.startedWork().get() != ChunkStatus.EMPTY)
                throw new ChunkProtocolException("holder/status reservation evidence changed");
        }
        @Override public boolean poiAlreadyInstalled(ChunkLoadGraph.Token token, CompletableFuture<?> source) {
            requireHolder(token);
            PoiStorageAccess storage = (PoiStorageAccess) map.phoenium$poi();
            return new PoiLoadBookkeeping(storage.phoenium$loadLock(), storage.phoenium$pendingLoads(),
                    storage.phoenium$loadedChunks()).alreadyInstalled(position.pack(), source);
        }
        @Override public void installPoi(ChunkLoadGraph.Token token, ChunkLoadGraph.PoiInput input,
                                         ChunkStageClaim claim) {
            requireHolder(token); claim.require(token.work().equals(claim.stage().work()) ? claim.stage() : null);
            if (!(input.packedResult() instanceof Optional<?> packedResult))
                throw new ChunkProtocolException("POI prefetch result type changed");
            PoiStorageAccess storage = (PoiStorageAccess) map.phoenium$poi();
            PoiLoadBookkeeping bookkeeping = new PoiLoadBookkeeping(storage.phoenium$loadLock(),
                    storage.phoenium$pendingLoads(), storage.phoenium$loadedChunks());
            long key = position.pack();
            if (!bookkeeping.needsInstallation(key, input.source())) return;
            try { MinecraftPoiInstaller.install((net.minecraft.world.level.chunk.storage.SectionStorage<?, ?>) map.phoenium$poi(),
                    position, packedResult.orElse(null)); }
            catch (Throwable failure) { throw failure instanceof RuntimeException runtime ? runtime
                    : new RuntimeException(failure); }
            bookkeeping.acknowledgeInstallation(key, input.source());
            poiInstalls++;
            trace("POI_INSTALLED", token, "Q=" + input.source());
        }
        @Override public LightInputService.BatchId prepareReload(ChunkLoadGraph graph, SerializableChunkData data) {
            if (data.chunkPos() != position && !data.chunkPos().equals(position))
                throw new ChunkProtocolException("parsed chunk position differs from load token");
            boolean sky = map.phoenium$level().dimensionType().hasSkyLight();
            ArrayList<LightInputService.SectionInput> inputs = new ArrayList<>();
            long occurrence = 1;
            for (SerializableChunkData.SectionData section : data.sectionData()) {
                String key = Long.toString(net.minecraft.core.SectionPos.of(position, section.y()).asLong());
                if (section.blockLight() != null)
                    inputs.add(new LightInputService.SectionInput(LightInputService.Layer.BLOCK, key, occurrence++));
                if (sky && section.skyLight() != null)
                    inputs.add(new LightInputService.SectionInput(LightInputService.Layer.SKY, key, occurrence++));
            }
            LightInputService.BatchId batch = new LightInputService.BatchId(graph.token().lifecycle(),
                    graph.token().work(), graph.parsedStage(), batchSequence.getAndIncrement());
            if (!services().light().register(batch, !inputs.isEmpty(), inputs))
                throw new ChunkProtocolException("reload light batch registration rejected");
            trace("LIGHT_REGISTERED", graph, "batch=" + batch.inputSequence() + " occurrences=" + (inputs.size() + (inputs.isEmpty() ? 0 : 1)));
            return batch;
        }
        @Override public ChunkAccess apply(ChunkLoadGraph.Token token, Optional<SerializableChunkData> data,
                                           LightInputService.BatchId batch, ChunkStageClaim claim) {
            requireHolder(token);
            try (SynchronousChunkInvocation ignored = new SynchronousChunkInvocation(claim, services(), position,
                    data.orElse(null), data.isPresent() ? Objects.requireNonNull(batch) : null,
                    map.phoenium$level().dimensionType().hasSkyLight())) {
                ChunkAccess result = map.phoenium$apply(position, data);
                if (data.isPresent()) {
                    LightInputService.Snapshot snapshot = services().light().snapshot(batch).orElseThrow(
                            () -> new ChunkProtocolException("reload apply returned without light batch state"));
                    String order = snapshot.occurrences().stream().map(occurrence ->
                            occurrence.id().occurrenceSequence() + ":" + occurrence.id().kind() + "@"
                                    + occurrence.id().sectionKey() + ":" + occurrence.state() + ":inserted="
                                    + occurrence.inserted() + ":delegated=" + occurrence.delegated())
                            .collect(java.util.stream.Collectors.joining(","));
                    trace("LIGHT_ACCEPTED", token, "batch=" + batch.inputSequence() + " stage="
                            + batch.stage().stageSequence() + " sealed=" + snapshot.sealed() + " receipt="
                            + snapshot.receiptPresent() + " order=[" + order + "]");
                }
                parsedApplications++;
                trace("PARSED_APPLIED", token, "reload=" + data.isPresent());
                return result;
            }
        }
        @Override public ChunkAccess recover(ChunkLoadGraph.Token token, Throwable failure, ChunkStageClaim claim) {
            requireHolder(token);
            try (SynchronousChunkInvocation ignored = new SynchronousChunkInvocation(claim, services(), position,
                    null, null, map.phoenium$level().dimensionType().hasSkyLight())) {
                ChunkAccess result = map.phoenium$recover(position, failure);
                recoveryApplications++;
                trace("RECOVERY_APPLIED", token, "cause=" + failure.getClass().getName());
                return result;
            }
        }
        @Override public ChunkResult<ChunkAccess> publish(ChunkLoadGraph.Token token,
                                                          ChunkLoadGraph.HolderInput input, ChunkStageClaim claim) {
            requireHolder(token); claim.require(claim.stage());
            ChunkResult<ChunkAccess> result = ((GenerationChunkHolderAccess) token.holder())
                    .phoenium$complete(step, input.value(), input.failure());
            publications++;
            trace("EMPTY_PUBLISHED", token, "failure=" + (input.failure() != null));
            return result;
        }
        @Override public void unload(ChunkLoadGraph.Token token) {
            if (token.reservation() != reservation || token.holder() != graphHolder(token))
                throw new ChunkProtocolException("private unload token changed");
            CompletableFuture<ChunkResult<ChunkAccess>> slot = reservation.statusSlots().get(ChunkStatus.EMPTY.getIndex());
            if (slot != null && !slot.isDone()) ((GenerationChunkHolderAccess) token.holder())
                    .phoenium$failAndClear(ChunkStatus.EMPTY.getIndex(), slot);
            privateUnloads++;
            trace("PRIVATE_UNLOADED", token, "slot=" + (slot == null ? "absent" : slot.isDone()));
        }
        @Override public void terminal(ChunkLoadGraph graph, ChunkCustodyModel.StageId stage,
                                       ChunkStageAdmission.Result reason, Throwable failure) {
            String detail = "stage=" + stage.kind() + "/" + stage.subKey() + "/" + stage.stageSequence()
                    + " reason=" + reason + (failure == null ? "" : " cause=" + failure);
            trace("TERMINAL", graph, detail);
        }
        @Override public void barrier(ChunkLoadGraph graph, ChunkCustodyModel.StageId stage, Throwable failure) {
            barriers++; authority.chunkBarrier(failure);
        }
        @Override public void settled(ChunkLoadGraph graph) {
            unbindGraph(graph);
            var publication = graph.publicationInner();
            if (publication.isDone() && !publication.isCompletedExceptionally()
                    && publication.getNow(null) != null && publication.getNow(null).isSuccess())
                services().settleFinishedExecution(graph.token().work(), graph.token().holder());
            else services().releaseFinishedWork(graph.token().work(), graph.token().holder());
            trace("GRAPH_SETTLED", graph, "retained=" + retainedGraphs());
        }
        private GenerationChunkHolder graphHolder(ChunkLoadGraph.Token token) { return token.holder(); }
    }

    private ChunkLoadGraph graphFor(ChunkLoadGraph.Token token) {
        indexGate.lock();
        try {
            Set<ChunkLoadGraph> graphs = holderGraphs.get(token.holder());
            if (graphs != null) for (ChunkLoadGraph graph : graphs) if (graph.token() == token) return graph;
            throw new ChunkProtocolException("load graph token is no longer bound");
        } finally { indexGate.unlock(); }
    }

    private void bindReturned(CompletableFuture<ChunkAccess> returned, ChunkLoadGraph graph) {
        indexGate.lock(); try {
            if (returnedIndex.put(returned, graph) != null) throw new ChunkProtocolException("returned future identity reused");
        } finally { indexGate.unlock(); }
    }
    private void bindPoi(CompletableFuture<?> poi, ChunkLoadGraph graph) {
        indexGate.lock(); try {
            ChunkLoadGraph prior = poiIndex.putIfAbsent(poi, graph);
            if (prior != null && prior != graph) throw new ChunkProtocolException("POI future shared across different work");
        } finally { indexGate.unlock(); }
    }
    private void bindHolderGraph(GenerationChunkHolder holder, ChunkLoadGraph graph) {
        indexGate.lock(); try {
            holderGraphs.computeIfAbsent(holder, ignored -> java.util.Collections.newSetFromMap(new IdentityHashMap<>())).add(graph);
        } finally { indexGate.unlock(); }
    }
    private void unbindGraph(ChunkLoadGraph graph) {
        indexGate.lock(); try {
            returnedIndex.values().removeIf(graph::equals);
            poiIndex.values().removeIf(graph::equals);
            Set<ChunkLoadGraph> graphs = holderGraphs.get(graph.token().holder());
            if (graphs != null) { graphs.remove(graph); if (graphs.isEmpty()) holderGraphs.remove(graph.token().holder()); }
        } finally { indexGate.unlock(); }
    }
    private void supersedeLifecycle(ChunkCustodyModel.LifecycleId lifecycle) {
        for (ChunkLoadGraph graph : graphs()) if (graph.token().lifecycle().equals(lifecycle)) graph.superseded();
        for (C2meGenerationGraph graph : c2meGraphs())
            if (graph.lifecycle.equals(lifecycle)) graph.control(ChunkStageAdmission.Result.SUPERSEDED);
        for (C2meFullGraph graph : c2meFullGraphs())
            if (graph.lifecycle.equals(lifecycle)) graph.control(ChunkStageAdmission.Result.SUPERSEDED);
        for (C2meAsyncReadGraph graph : c2meReadGraphs())
            if (graph.lifecycle.equals(lifecycle)) graph.control(ChunkStageAdmission.Result.SUPERSEDED);
    }
    private List<ChunkLoadGraph> graphs() {
        indexGate.lock(); try {
            ArrayList<ChunkLoadGraph> result = new ArrayList<>(); holderGraphs.values().forEach(result::addAll);
            return List.copyOf(result);
        } finally { indexGate.unlock(); }
    }
    private List<C2meGenerationGraph> c2meGraphs() {
        c2meGate.lock(); try { return List.copyOf(c2meGenerationGraphs); } finally { c2meGate.unlock(); }
    }
    private List<C2meFullGraph> c2meFullGraphs() {
        c2meGate.lock(); try { return List.copyOf(c2meFullGraphs); } finally { c2meGate.unlock(); }
    }
    private List<C2meAsyncReadGraph> c2meReadGraphs() {
        c2meGate.lock(); try { return List.copyOf(c2meReadGraphs); } finally { c2meGate.unlock(); }
    }
    private void unbindC2me(C2meGenerationGraph graph) {
        c2meGate.lock();
        try {
            c2meSlots.remove(graph.observation.statusSlot(), graph);
            c2meGenerationGraphs.remove(graph);
        } finally { c2meGate.unlock(); }
    }
    private void unbindC2meFull(C2meFullGraph graph) {
        c2meGate.lock();
        try {
            c2meFullSlots.remove(graph.observation.statusSlot(), graph);
            c2meFullGraphs.remove(graph);
        } finally { c2meGate.unlock(); }
    }
    private RuntimeException breach(Throwable failure) {
        barriers++; authority.chunkBarrier(failure);
        return failure instanceof RuntimeException runtime ? runtime : new ChunkProtocolException("chunk binding failure", failure);
    }
    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return failure instanceof RuntimeException runtime ? runtime
                : new ChunkProtocolException("C2ME provider continuation failed", failure);
    }
    private void trace(String event, ChunkLoadGraph graph, String detail) { trace(event, graph.token(), detail); }
    private void trace(String event, ChunkLoadGraph.Token token, String detail) {
        if (!Boolean.getBoolean("phoenium.test.chunkBindingTrace")) return;
        logger.info("PHOENIUM_CHUNK_BINDING {} world={} chunk={},{} lifecycle={} work={} stageDetail={}", event,
                token.lifecycle().world().value(), token.lifecycle().chunkKey().x(), token.lifecycle().chunkKey().z(),
                token.lifecycle().holderActivationSequence(), token.work().workSequence(), detail);
    }
    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(failure); return result;
    }
}
