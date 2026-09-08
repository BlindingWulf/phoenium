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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;

/** Production Decision-A binding for the exact Minecraft 26.2 EMPTY load graph. */
public final class ChunkBindingRuntime {
    public interface Authority {
        WorldAuthority requireChunkWorld(ServerLevel level);
        void chunkBarrier(Throwable failure);
    }
    public record WorldAuthority(WorldId world, long worldInstanceSequence,
                                 ChunkPrerequisiteServices services, RegionDirectory directory,
                                 Map<RegionId, RegionLeaseManager> leases, FailureBarrier barrier) {
        public WorldAuthority {
            Objects.requireNonNull(world); Objects.requireNonNull(services); Objects.requireNonNull(directory);
            Objects.requireNonNull(leases); Objects.requireNonNull(barrier); leases = Map.copyOf(leases);
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
    private final Map<WorldId, WorldBinding> worlds = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Delivery> deliveries = new ConcurrentLinkedDeque<>();
    private volatile boolean open = true;
    private long batchSequence;
    private long registrations, exactCaptures, poiInstalls, parsedApplications, recoveryApplications;
    private long publications, privateUnloads, staleReroutes, duplicateApplications, barriers;

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
        for (var world : java.util.List.copyOf(worlds.values())) failures.run(world.authority.services()::stopResidentHolders);
        Delivery delivery;
        while ((delivery = deliveries.pollFirst()) != null) {
            Delivery terminal = delivery;
            failures.run(() -> terminal.stage().control(ChunkStageAdmission.Result.SHUTDOWN));
        }
        if (Boolean.getBoolean("phoenium.test.chunkBindingTrace"))
            failures.run(() -> logger.info("PHOENIUM_CHUNK_BINDING STOPPED {}", diagnostics()));
        indexGate.lock(); try {
            if (holderGraphs.isEmpty() && returnedIndex.isEmpty() && poiIndex.isEmpty()) worlds.clear();
        } finally { indexGate.unlock(); }
        failures.rethrow();
    }

    public int retainedGraphs() { indexGate.lock(); try { return holderGraphs.values().stream().mapToInt(Set::size).sum(); } finally { indexGate.unlock(); } }
    public int returnedIndexEntries() { indexGate.lock(); try { return returnedIndex.size(); } finally { indexGate.unlock(); } }
    public int poiIndexEntries() { indexGate.lock(); try { return poiIndex.size(); } finally { indexGate.unlock(); } }
    public String diagnostics() {
        ServiceCounts service = serviceCounts();
        return "registrations=" + registrations + " exactCaptures=" + exactCaptures + " poiInstalls=" + poiInstalls
                + " parsedApplications=" + parsedApplications + " recoveryApplications=" + recoveryApplications
                + " publications=" + publications + " privateUnloads=" + privateUnloads + " staleReroutes=" + staleReroutes
                + " duplicateApplications=" + duplicateApplications + " barriers=" + barriers
                + " retainedGraphs=" + retainedGraphs() + " returnedIndex=" + returnedIndexEntries()
                + " poiIndex=" + poiIndexEntries() + " synchronousInvocations=" + SynchronousChunkInvocation.retainedEntries()
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
                    graph.token().work(), graph.parsedStage(), batchSequence++);
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
                                    + occurrence.inserted()).collect(java.util.stream.Collectors.joining(","));
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
    }
    private List<ChunkLoadGraph> graphs() {
        indexGate.lock(); try {
            ArrayList<ChunkLoadGraph> result = new ArrayList<>(); holderGraphs.values().forEach(result::addAll);
            return List.copyOf(result);
        } finally { indexGate.unlock(); }
    }
    private RuntimeException breach(Throwable failure) {
        barriers++; authority.chunkBarrier(failure);
        return failure instanceof RuntimeException runtime ? runtime : new ChunkProtocolException("chunk binding failure", failure);
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
