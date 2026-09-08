package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.RoutingKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.level.*;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;

/**
 * FULL registration, preparation, finishing and separately admitted publication. The production
 * source selects this graph for an explicitly enabled disposable integration session.
 * The one custody gate protects metadata, never callbacks, submissions or future completion.
 */
public final class FullRegistrationRuntime {
    private static final ThreadLocal<Invocation> SYNCHRONOUS = new ThreadLocal<>();
    private record Invocation(FullRegistrationRuntime runtime, GeneratingChunkMap map,
                              GenerationChunkHolder holder, ChunkStep step,
                              StaticCache2D<GenerationChunkHolder> cache, Producer producer) { }
    private record Delivery(RegionId owner, TopologyGeneration generation, PendingVanillaStage<?, ?> stage) { }
    private final ServerLevel level;
    private final net.minecraft.server.MinecraftServer server;
    final ChunkBindingRuntime.WorldAuthority authority;
    final ChunkPrerequisiteServices services;
    final ChunkCustodyModel custody;
    private final IdentityHashMap<GenerationChunkHolder, Generation> generations = new IdentityHashMap<>();
    private final IdentityHashMap<CompletableFuture<ChunkAccess>, Graph> returnedIndex = new IdentityHashMap<>();
    private final Set<Graph> graphs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ConcurrentLinkedDeque<Delivery> deliveries = new ConcurrentLinkedDeque<>();
    private final ChunkStageExecutor executor;
    final ChunkStageDeliveryPump pump;
    private boolean open = true;
    private long registrations, captures, releases, preparationCalls, finishingCalls, publicationCalls;
    private final Thread observationThread;
    public final FullInitializedInputs inputs;
    public final FullPublishedInputs published;
    private final AutoCloseable publishedSubscription;
    private final AutoCloseable inputRouteSubscription;
    private AutoCloseable inputViewSubscription;
    private final FullRegionalEnclosure enclosure;

    public FullRegistrationRuntime(ServerLevel level, ChunkBindingRuntime.WorldAuthority authority) {
        this(level, authority, FullInitializedInputs.Limits.production());
    }
    public FullRegistrationRuntime(ServerLevel level, ChunkBindingRuntime.WorldAuthority authority, FullInitializedInputs.Limits limits) {
        this(level, authority, limits, null);
    }
    public FullRegistrationRuntime(ServerLevel level, ChunkBindingRuntime.WorldAuthority authority,
                                   FullInitializedInputs.Limits limits, FullRegionalEnclosure enclosure) {
        this.level = Objects.requireNonNull(level); this.authority = Objects.requireNonNull(authority);
        this.enclosure = enclosure;
        server = Objects.requireNonNull(level.getServer());
        observationThread = Objects.requireNonNull(((FullInputObservationThread) level.getChunkSource()).phoenium$inputThread());
        services = authority.services(); custody = services.custody();
        inputs = new FullInitializedInputs(this, limits);
        published = services.publishedInputs(level, authority);
        publishedSubscription = published.changes().subscribe(inputs::changed);
        inputRouteSubscription = authority.directory().observeChanges(inputs::changed);
        executor = new ChunkStageExecutor(new ChunkStageAdmission(custody, authority.directory(), authority.leases(), authority.barrier()));
        pump = new ChunkStageDeliveryPump(authority.directory(), (route, pending) -> {
            deliveries.addLast(new Delivery(route.region(), route.generation(), pending));
            return ChunkStageOwnerGateway.Delivery.QUEUED;
        }, executor);
    }
    ServerLevel level() { return level; }
    net.minecraft.server.MinecraftServer server() { return server; }
    void requireObservationThread() {
        if (Thread.currentThread() != observationThread) throw new ChunkProtocolException("FULL input directory observation outside its serial publication thread");
    }
    ChunkHolder visible(net.minecraft.world.level.ChunkPos position) {
        requireObservationThread();
        var view = (FullInputView) level.getChunkSource().chunkMap;
        if (inputViewSubscription == null && open) inputViewSubscription = view.phoenium$inputChanges().subscribe(inputs::changed);
        return view.phoenium$visibleInput(position.pack());
    }
    List<Graph> graphSnapshot() { custody.gate().lock(); try { return List.copyOf(graphs); } finally { custody.gate().unlock(); } }
    Optional<Graph> findGraph(GenerationChunkHolder holder) {
        return graphSnapshot().stream().filter(graph -> graph.holder == holder).findFirst();
    }
    boolean biomesReleased(GenerationChunkHolder holder) {
        custody.gate().lock(); try {
            var generation = generations.get(holder);
            var producer = generation == null ? null : generation.steps.get(ChunkStatus.BIOMES);
            return producer == null || producer.finished && producer.failure == null;
        } finally { custody.gate().unlock(); }
    }

    /** Called only at the map invocation dominated by the holder's winning acquireStatusBump. */
    public static CompletableFuture<ChunkAccess> applyWinning(GeneratingChunkMap map, GenerationChunkHolder holder,
            ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, Supplier<CompletableFuture<ChunkAccess>> original) {
        var runtime = map instanceof FullGenerationSource source ? source.phoenium$fullRegistration() : null;
        if (runtime == null) return FullPublishedInputs.winning(map, holder, step, cache, original);
        return runtime.winning(map, holder, step, cache, original);
    }

    private CompletableFuture<ChunkAccess> winning(GeneratingChunkMap map, GenerationChunkHolder holder,
            ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, Supplier<CompletableFuture<ChunkAccess>> original) {
        FullHolderEvidence evidence = (FullHolderEvidence) holder;
        if (evidence.phoenium$startedWork().get() != step.targetStatus() || cache.get(holder.getPos().x(), holder.getPos().z()) != holder)
            throw new ChunkProtocolException("FULL generation did not receive the actual winning reservation");
        if (evidence.phoenium$generationRefs().get() <= 0 || evidence.phoenium$generationSaveSync().isDone())
            throw new ChunkProtocolException("winning generation reservation has no live generation borrow");
        Producer producer;
        List<Generation> watch;
        custody.gate().lock();
        try {
            if (!open) throw new ChunkProtocolException("generation registration after shutdown");
            Generation generation = generations.computeIfAbsent(holder, Generation::new);
            if (generation.steps.containsKey(step.targetStatus())) throw new ChunkProtocolException("repeated winning status reservation");
            producer = new Producer(generation, step);
            generation.steps.put(step.targetStatus(), producer);
            int radius = Math.max(0, step.blockStateWriteRadius());
            if (step.targetStatus() != ChunkStatus.FULL) {
                for (int x = holder.getPos().x() - radius; x <= holder.getPos().x() + radius; x++)
                    for (int z = holder.getPos().z() - radius; z <= holder.getPos().z() + radius; z++) {
                        Generation target = generations.computeIfAbsent(cache.get(x, z), Generation::new);
                        if (target.prepared != null || target.preparing)
                            throw new ChunkProtocolException("generation writer conflicts with reserved FULL preparation");
                        target.writers.add(producer); producer.targets.add(target);
                    }
            }
            watch = generations.values().stream().filter(candidate -> !candidate.watched).toList();
            watch.forEach(candidate -> candidate.watched = true);
        } finally { custody.gate().unlock(); }
        watch.forEach(generation -> generation.retention.whenComplete((ignored, failure) -> cleanupGenerations()));
        Invocation previous = SYNCHRONOUS.get();
        SYNCHRONOUS.set(new Invocation(this, map, holder, step, cache, producer));
        final CompletableFuture<ChunkAccess> returned;
        try { returned = Objects.requireNonNull(original.get()); }
        catch (Throwable failure) { release(producer, null, failure); throw failure; }
        finally { if (previous == null) SYNCHRONOUS.remove(); else SYNCHRONOUS.set(previous); }
        custody.gate().lock();
        try {
            producer.returned = returned;
            if (producer.graph != null) {
                producer.graph.returned = returned;
                published.admittedReturned(producer.graph.publicationEntry, returned);
                returnedIndex.put(returned, producer.graph);
            }
        } finally { custody.gate().unlock(); }
        // Install the actual returned-future index before an already-completed producer can run.
        returned.whenComplete((value, failure) -> release(producer, value, failure));
        return returned;
    }

    /** Replaces the exact supplyAsync call inside the original wrapper, before any future escapes. */
    public static CompletableFuture<ChunkAccess> registerAsync(Supplier<ChunkAccess> original, Executor originalExecutor,
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess input, GenerationChunkHolder holder, Supplier<ChunkAccess> preparation,
            Function<LevelChunk, ChunkAccess> finishing) {
        Invocation invocation = SYNCHRONOUS.get();
        if (invocation == null) return FullPublishedInputs.originalAsync(original, originalExecutor, context, step, cache, input, holder);
        if (invocation.holder != holder || invocation.step != step || invocation.cache != cache
                || step.targetStatus() != ChunkStatus.FULL || context.level() != invocation.runtime.level)
            throw new ChunkProtocolException("async FULL does not match its finite winning invocation");
        if (step != ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)
                && step != ChunkPyramid.LOADING_PYRAMID.getStepTo(ChunkStatus.FULL))
            throw new ChunkProtocolException("unaudited FULL generation step");
        return invocation.runtime.register(invocation, context, input, preparation, finishing, originalExecutor);
    }

    private CompletableFuture<ChunkAccess> register(Invocation invocation, WorldGenContext context, ChunkAccess input,
                                                     Supplier<ChunkAccess> preparation, Function<LevelChunk, ChunkAccess> finishing,
                                                     Executor originalExecutor) {
        var holder = invocation.holder;
        var key = new RoutingKey(authority.world(), holder.getPos().x(), holder.getPos().z());
        var lifecycle = services.lifecycles().activateHolder(authority.worldInstanceSequence(), key, holder);
        var work = services.works().beginOrResolveSharedWork(lifecycle,
                new ChunkWorkRegistry.WorkReservation("generation-status", "FULL", 0));
        services.trackHolder(lifecycle, holder);
        Graph graph = new Graph(invocation, context, input, preparation, finishing, lifecycle, work);
        custody.gate().lock();
        try {
            if (invocation.producer.graph != null) throw new ChunkProtocolException("duplicate async FULL registration");
            invocation.producer.graph = graph; graphs.add(graph); registrations++;
        } finally { custody.gate().unlock(); }
        if (graph.clock != null) graph.clock.subscribe(graph);
        inputs.changed();
        // All pending stages exist before the executor is armed. Inline execution only arms metadata.
        try { originalExecutor.execute(graph::arm); }
        catch (Throwable failure) {
            graph.acknowledgeRegistration();
            graph.stop(ChunkStageAdmission.Result.FAILED_BARRIER, failure);
        }
        return graph.conversion;
    }

    public static CompletableFuture<ChunkResult<ChunkAccess>> attach(GeneratingChunkMap map,
            CompletableFuture<ChunkAccess> returned, GenerationChunkHolder holder, ChunkStep step,
            BiFunction<ChunkAccess, Throwable, ChunkResult<ChunkAccess>> original) {
        FullRegistrationRuntime runtime = map instanceof FullGenerationSource source ? source.phoenium$fullRegistration() : null;
        if (runtime == null) return FullPublishedInputs.attach(map, returned, holder, step, original);
        Graph graph;
        runtime.custody.gate().lock();
        try {
            graph = runtime.returnedIndex.get(returned);
            if (graph == null) return null;
            if (graph.invocation.map != map || graph.holder != holder || graph.invocation.step != step || graph.returned != returned)
                throw new ChunkProtocolException("exact FULL returned future/holder/work correlation changed");
            runtime.returnedIndex.remove(returned); runtime.captures++;
        } finally { runtime.custody.gate().unlock(); }
        return graph.attach(original);
    }

    private void release(Producer producer, ChunkAccess value, Throwable failure) {
        List<Graph> wake;
        custody.gate().lock();
        try {
            if (producer.finished) return;
            producer.finished = true; producer.value = value; producer.failure = failure; releases++;
            for (Generation target : producer.targets) { target.writers.remove(producer); target.revision++; }
            wake = List.copyOf(graphs);
        } finally { custody.gate().unlock(); }
        wake.forEach(Graph::wake);
        inputs.changed();
        cleanupGenerations();
    }

    public boolean pumpOne() {
        if (OwnershipContext.current().isPresent()) return false;
        if (enclosure != null && enclosure.advance()) return true;
        pump.retryTopology();
        Delivery delivery = deliveries.pollFirst();
        if (delivery == null) return inputs.pump();
        delivery.stage.prepareFullServices();
        var result = executor.execute(delivery.stage, delivery.owner, delivery.generation);
        if (result == ChunkStageAdmission.Result.STALE_ROUTE)
            pump.deliverPublished(delivery.stage);
        else if (result == ChunkStageAdmission.Result.NOT_READY) {
            if (authority.directory().pendingChange(delivery.stage.chunk()).filter(change -> !change.completed()).isPresent())
                pump.deliverPublished(delivery.stage);
            else delivery.stage.awaitFullEvidence();
        }
        return true;
    }
    public void shutdown() {
        List<Graph> snapshot;
        custody.gate().lock(); try { open = false; snapshot = List.copyOf(graphs); } finally { custody.gate().unlock(); }
        var failures = new FullInputNotifications.Failures();
        for (Graph graph : snapshot) failures.run(() -> graph.stop(ChunkStageAdmission.Result.SHUTDOWN, null));
        failures.run(published::shutdown);
        failures.run(services::stopResidentHolders);
        failures.run(publishedSubscription::close);
        failures.run(inputRouteSubscription::close);
        var viewSubscription = inputViewSubscription; inputViewSubscription = null;
        if (viewSubscription != null) failures.run(viewSubscription::close);
        failures.run(pump::shutdown);
        failures.run(() -> { while (pumpOne()) { } });
        failures.run(this::cleanupGenerations);
        failures.rethrow();
    }
    public void retire(GenerationChunkHolder holder) {
        List<Graph> snapshot;
        custody.gate().lock(); try { snapshot = graphs.stream().filter(graph -> graph.holder == holder).toList(); }
        finally { custody.gate().unlock(); }
        var failures = new FullInputNotifications.Failures();
        failures.run(() -> FullPublishedInputs.retired(holder));
        failures.run(() -> ChunkHolderLifetime.retired(holder));
        for (Graph graph : snapshot) {
            failures.run(() -> services.lifecycles().retireHolder(graph.lifecycle, holder));
            failures.run(() -> graph.stop(ChunkStageAdmission.Result.SUPERSEDED, null));
        }
        failures.rethrow();
    }
    public Graph graph(GenerationChunkHolder holder) {
        custody.gate().lock(); try { return graphs.stream().filter(graph -> graph.holder == holder).findFirst().orElseThrow(); }
        finally { custody.gate().unlock(); }
    }
    public Map<String, Long> measurements() {
        custody.gate().lock(); try {
            return Map.of("registrations", registrations, "captures", captures, "producerReleases", releases,
                    "preparationCalls", preparationCalls, "graphs", (long) graphs.size(), "returnedIndex", (long) returnedIndex.size(),
                    "generationHolders", (long) generations.size(), "deliveries", (long) deliveries.size(),
                    "finishingCalls", finishingCalls, "publicationCalls", publicationCalls);
        } finally { custody.gate().unlock(); }
    }
    private void cleanupGenerations() {
        custody.gate().lock(); try {
            generations.values().removeIf(generation -> generation.retention.isDone()
                    && generation.evidence.phoenium$generationRefs().get() == 0
                    && generation.writers.isEmpty() && graphs.stream().noneMatch(graph -> graph.holder == generation.holder));
        } finally { custody.gate().unlock(); }
    }

    private static final class Generation {
        final GenerationChunkHolder holder;
        final FullHolderEvidence evidence;
        final CompletableFuture<Void> retention;
        final Map<ChunkStatus, Producer> steps = new IdentityHashMap<>();
        final Set<Producer> writers = Collections.newSetFromMap(new IdentityHashMap<>());
        long revision;
        boolean preparing;
        boolean watched;
        LevelChunk prepared;
        Generation(GenerationChunkHolder holder) {
            this.holder = holder; evidence = (FullHolderEvidence) holder; retention = evidence.phoenium$generationSaveSync();
        }
    }
    private record Dependency(GenerationChunkHolder holder, ChunkStatus status,
                              CompletableFuture<ChunkResult<ChunkAccess>> slot, Producer releasing) { }
    private static final class Producer {
        final Generation generation;
        final ChunkStep step;
        final List<Generation> targets = new ArrayList<>();
        CompletableFuture<ChunkAccess> returned;
        ChunkAccess value;
        Throwable failure;
        boolean finished;
        Graph graph;
        Producer(Generation generation, ChunkStep step) { this.generation = generation; this.step = step; }
    }

    public record Prepared(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work,
                           GenerationChunkHolder holder, ChunkCustodyModel.StageId attempt, LevelChunk actual) { }

    /** Only this exact graph can produce and authenticate its private terminal control. */
    public static final class Stopped extends RuntimeException {
        private final Object terminalIdentity;
        public final ChunkStageAdmission.Result reason;
        private Stopped(Graph graph, ChunkStageAdmission.Result reason, Throwable cause) {
            super("private FULL " + reason, cause); this.terminalIdentity = graph.terminalIdentity; this.reason = reason;
        }
    }

    public final class Graph {
        private final Invocation invocation;
        public final GenerationChunkHolder holder;
        public final ChunkCustodyModel.LifecycleId lifecycle;
        public final ChunkCustodyModel.WorkId work;
        private final ChunkAccess input;
        private final WorldGenContext context;
        private final FullHolderEvidence evidence;
        private final CompletableFuture<Void> retention;
        private final List<CompletableFuture<ChunkResult<ChunkAccess>>> slots;
        private final List<Dependency> required = new ArrayList<>();
        private final Object containerFactory;
        private final Object dimensionType;
        private final FullPreparationClock clock;
        private boolean clockReserved;
        public final PendingVanillaStage<ChunkAccess, Prepared> preparation;
        public final PendingVanillaStage<Prepared, ChunkAccess> finishing;
        public final PendingVanillaStage<FullResult, ChunkResult<ChunkAccess>> publication;
        private final FullPublishedInputs.Entry publicationEntry;
        private final CompletableFuture<ChunkResult<ChunkAccess>> publicationResult = new CompletableFuture<>();
        private BiFunction<ChunkAccess, Throwable, ChunkResult<ChunkAccess>> originalPublication;
        private FullResult fullResult;
        private FullInitializedInputs.Receipt claimedInputs;
        private FullFinishingServices.Reservation serviceReservation;
        private FullLegacyOwners.Plan legacyOwners;
        private boolean finished, finishingParked;
        private final CompletableFuture<ChunkAccess> conversion = new CompletableFuture<>();
        private CompletableFuture<ChunkAccess> returned;
        private CompletableFuture<ChunkResult<ChunkAccess>> adjacent;
        private boolean armed, attached, cleaned, queued, evidenceParked;
        private int consumers;
        private Prepared prepared;
        private Stopped stopped;
        private final Object terminalIdentity = new Object();
        private boolean registrationSettled;
        private volatile ChunkProtocolException protocolFailure;
        private FullSavedInput savedInput;
        private final Runnable savedChanged = () -> inputs.changed();
        private boolean inputRequested = true;
        private boolean finishingInputBound;
        private FullInitializedInputs.Evaluation lastInputEvaluation;

        Graph(Invocation invocation, WorldGenContext context, ChunkAccess input, Supplier<ChunkAccess> operation,
              Function<LevelChunk, ChunkAccess> finishingOperation,
              ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work) {
            this.invocation = invocation; this.holder = invocation.holder; this.lifecycle = lifecycle; this.work = work;
            this.context = context; this.input = input; evidence = (FullHolderEvidence) holder;
            publicationEntry = published.admitted(invocation.map, holder, invocation.step, invocation.cache, work, input);
            retention = evidence.phoenium$generationSaveSync();
            containerFactory = context.level().palettedContainerFactory();
            dimensionType = context.level().dimensionType();
            clock = level.getLevelData() instanceof FullPreparationClockAccess access ? access.phoenium$preparationClock() : null;
            slots = new ArrayList<>();
            for (int index = 0; index < ChunkStatus.FULL.getIndex(); index++) slots.add(evidence.phoenium$futures().get(index));
            var dependencies = invocation.step.directDependencies();
            int radius = dependencies.getRadius();
            custody.gate().lock();
            try {
                for (int x = holder.getPos().x() - radius; x <= holder.getPos().x() + radius; x++)
                    for (int z = holder.getPos().z() - radius; z <= holder.getPos().z() + radius; z++) {
                        GenerationChunkHolder dependencyHolder = invocation.cache.get(x, z);
                        Generation dependency = generations.get(dependencyHolder);
                        int distance = holder.getPos().getChessboardDistance(x, z);
                        ChunkStatus status = dependencies.get(distance);
                        required.add(new Dependency(dependencyHolder, status,
                                ((FullHolderEvidence) dependencyHolder).phoenium$futures().get(status.getIndex()),
                                dependency == null ? null : dependency.steps.get(status)));
                    }
            } finally { custody.gate().unlock(); }
            var attempt = new ChunkCustodyModel.StageId(work, ChunkStageKind.FULL_PREPARE, "FULL", 0);
            preparation = new PendingVanillaStage<>(custody, lifecycle, work, attempt, new TaskCorrelationId(work.workSequence()),
                    (value, claim) -> {
                        claim.require(attempt);
                        preparationCalls++;
                        try {
                            captureSavedInput();
                            LevelChunk actual = (LevelChunk) operation.get();
                            if (actual.getLevel() != level) throw new ChunkProtocolException("FULL preparation returned a foreign world object");
                            if (protocolFailure != null) throw protocolFailure;
                            return new Prepared(lifecycle, work, holder, attempt, actual);
                        } catch (Throwable failure) {
                            if (protocolFailure != null && protocolFailure != failure) {
                                protocolFailure.addSuppressed(failure); throw protocolFailure;
                            }
                            throw failure;
                        } finally { if (clockReserved) clock.release(this); }
                    });
            preparation.requireFull(this);
            finishing = new PendingVanillaStage<>(custody, lifecycle, work,
                    new ChunkCustodyModel.StageId(work, ChunkStageKind.FULL_FINISH, "FULL", 0),
                    new TaskCorrelationId(work.workSequence()), (value, claim) -> {
                        claim.require(new ChunkCustodyModel.StageId(work, ChunkStageKind.FULL_FINISH, "FULL", 0));
                        finishingCalls++;
                        try (var shared = serviceReservation.enter(claim);
                             var owners = legacyOwners.enter(claim);
                             var contexts = claimedInputs.contexts() == null ? null : claimedInputs.contexts().enter(claim);
                             var queries = FullFinishingQueries.enter(FullRegistrationRuntime.this, this, claim, claimedInputs)) {
                            ChunkAccess result = finishingOperation.apply(value.actual());
                            if (result != value.actual()) throw new ChunkProtocolException("original FULL suffix changed prepared object identity");
                            return result;
                        } finally { claimedInputs = null; }
                    });
            finishing.requireFull(this);
            publication = new PendingVanillaStage<>(custody, lifecycle, work,
                    new ChunkCustodyModel.StageId(work, ChunkStageKind.STATUS_PUBLISH, "FULL", 0),
                    new TaskCorrelationId(work.workSequence()), (value, claim) -> {
                        claim.require(new ChunkCustodyModel.StageId(work, ChunkStageKind.STATUS_PUBLISH, "FULL", 0));
                        if (value != fullResult || value.returned != returned || originalPublication == null)
                            throw new ChunkProtocolException("FULL publication lost original adjacent R result");
                        publicationCalls++;
                        published.admittedPublishing(publicationEntry, value.failure == null ? value.value : null, claim);
                        return originalPublication.apply(value.value, value.failure);
                    });
            publication.requireFull(this);
            preparation.outcome().thenAccept(this::preparedOutcome);
            finishing.outcome().thenAccept(this::finishedOutcome);
            publication.outcome().thenAccept(this::publishedOutcome);
        }

        void arm() {
            custody.gate().lock(); try { armed = true; registrationSettled = true; } finally { custody.gate().unlock(); }
            wake();
        }
        void acknowledgeRegistration() {
            custody.gate().lock(); try { registrationSettled = true; } finally { custody.gate().unlock(); }
        }
        void protocolFailure(ChunkProtocolException failure) { protocolFailure = failure; }

        CompletableFuture<ChunkResult<ChunkAccess>> attach(BiFunction<ChunkAccess, Throwable, ChunkResult<ChunkAccess>> original) {
            // R is already correlated. Do not arm admission until handle's result reference is retained.
            originalPublication = Objects.requireNonNull(original);
            var result = returned.handle((value, failure) -> {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (cause instanceof Stopped control && control == stopped && control.terminalIdentity == terminalIdentity)
                    return CompletableFuture.completedFuture(GenerationChunkHolder.UNLOADED_CHUNK);
                fullResult = new FullResult(returned, value, failure);
                if (publication.bindInput(fullResult)) pump.publishAndDeliver(publication);
                return publicationResult;
            }).thenCompose(Function.identity());
            custody.gate().lock(); try { adjacent = result; attached = true; } finally { custody.gate().unlock(); }
            result.whenComplete((ignored, failure) -> cleanup());
            retention.whenComplete((ignored, failure) -> cleanup());
            wake();
            return result;
        }

        /** Called inside the existing claim fence; no future completion or callback occurs here. */
        LightInputService.Readiness readinessLocked(ChunkStageKind kind) {
            if (kind == ChunkStageKind.FULL_FINISH) {
                lastInputEvaluation = inputs.inspect(this);
                if (lastInputEvaluation.state() != FullInitializedInputs.State.SATISFIED
                        || serviceReservation == null || !serviceReservation.valid(finishing.stage(), holder)
                        || legacyOwners == null || !legacyOwners.valid(finishing.stage(), savedSnapshot()))
                    return LightInputService.Readiness.NOT_REGISTERED;
                if (prepared == null || stopped != null || !services.lifecycles().isCurrent(lifecycle))
                    return LightInputService.Readiness.STALE;
                var route = authority.directory().resolve(lifecycle.chunkKey()).orElse(null);
                if (route == null) return LightInputService.Readiness.NOT_REGISTERED;
                for (var target : inputReceipt().targets()) {
                    if (((target.holder() != null && target.availability() != FullInitializedInputs.Availability.ABSENT)
                            || target.purposes().contains(FullInitializedInputs.Purpose.ENTITY_CONTEXT))
                            && !route.equals(target.route())) return LightInputService.Readiness.NOT_REGISTERED;
                    var generation = generations.get(target.holder());
                    if (target.availability() != FullInitializedInputs.Availability.BIOMES
                            && generation != null && !generation.writers.isEmpty()) return LightInputService.Readiness.NOT_REGISTERED;
                }
                return LightInputService.Readiness.REGISTERED;
            }
            if (kind == ChunkStageKind.STATUS_PUBLISH) {
                if (stopped != null || !custody.metadataEntitledLocked(work)
                        || !services.lifecycles().isCurrent(lifecycle)) return LightInputService.Readiness.STALE;
                if (fullResult == null || fullResult.returned != returned || !returned.isDone())
                    return LightInputService.Readiness.NOT_REGISTERED;
                if (fullResult.failure == null && (!finished || prepared == null || fullResult.value != prepared.actual()
                        || custody.stageState(finishing.stage()).orElse(null) != ChunkCustodyModel.StageState.APPLIED))
                    return LightInputService.Readiness.FAILED;
                return LightInputService.Readiness.REGISTERED;
            }
            if (kind != ChunkStageKind.FULL_PREPARE) return LightInputService.Readiness.FAILED;
            if (!attached || !armed) return LightInputService.Readiness.NOT_REGISTERED;
            if (stopped != null || !custody.metadataEntitledLocked(work)) return LightInputService.Readiness.STALE;
            if (context.level() != level || level.getServer() != server || evidence.phoenium$startedWork().get() != ChunkStatus.FULL
                    || invocation.cache.get(holder.getPos().x(), holder.getPos().z()) != holder)
                return LightInputService.Readiness.STALE;
            if (evidence.phoenium$generationRefs().get() <= 0 || retention != evidence.phoenium$generationSaveSync() || retention.isDone())
                return LightInputService.Readiness.NOT_REGISTERED;
            if (input != holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN)) return LightInputService.Readiness.STALE;
            for (int index = 0; index < slots.size(); index++) {
                var slot = slots.get(index);
                if (slot != evidence.phoenium$futures().get(index)) return LightInputService.Readiness.STALE;
                if (slot == null || !slot.isDone() || slot.isCompletedExceptionally()) return LightInputService.Readiness.NOT_REGISTERED;
                if (slot.getNow(null) == null || slot.getNow(null).orElse(null) != input) return LightInputService.Readiness.STALE;
            }
            for (Dependency dependency : required) {
                var slot = dependency.slot;
                if (slot == null || !slot.isDone() || slot.isCompletedExceptionally()) return LightInputService.Readiness.NOT_REGISTERED;
                if (slot != ((FullHolderEvidence) dependency.holder).phoenium$futures().get(dependency.status.getIndex())
                        || slot.getNow(null).orElse(null) != input) return LightInputService.Readiness.STALE;
                Producer releasing = dependency.releasing;
                if (releasing != null && (!releasing.finished || releasing.failure != null || releasing.value != input))
                    return LightInputService.Readiness.NOT_REGISTERED;
            }
            if (!invocation.producer.generation.writers.isEmpty()) return LightInputService.Readiness.NOT_REGISTERED;
            if (containerFactory == null || containerFactory != level.palettedContainerFactory()
                    || dimensionType != level.dimensionType()) return LightInputService.Readiness.STALE;
            if (needsClock() && (clock == null || !(level.getLevelData() instanceof FullPreparationClockAccess clockAccess)
                    || clockAccess.phoenium$preparationClock() != clock || !clock.available()))
                return LightInputService.Readiness.NOT_REGISTERED;
            return LightInputService.Readiness.REGISTERED;
        }
        private boolean needsClock() {
            return !(input instanceof ImposterProtoChunk) && ((ProtoChunk) input).getBlockEntities().values().stream()
                    .anyMatch(entity -> entity instanceof net.minecraft.world.level.block.entity.PotentSulfurBlockEntity);
        }
        boolean reserveLocked(ChunkStageKind kind) {
            if (kind == ChunkStageKind.FULL_FINISH) {
                if (readinessLocked(kind) != LightInputService.Readiness.REGISTERED) return false;
                claimedInputs = inputReceipt(); return true;
            }
            if (kind != ChunkStageKind.FULL_PREPARE) return true;
            if (needsClock() && !(clockReserved = clock != null && clock.reserve(this))) return false;
            invocation.producer.generation.preparing = true; return true;
        }
        void unreserveLocked(ChunkStageKind kind) {
            if (kind == ChunkStageKind.FULL_FINISH) { claimedInputs = null; return; }
            if (kind != ChunkStageKind.FULL_PREPARE) return;
            if (clockReserved) { clock.release(this); clockReserved = false; }
            invocation.producer.generation.preparing = false;
        }
        public List<String> missingFinishingBindings() {
            if (finished) return List.of();
            var missing = new ArrayList<String>();
            if (inputEvaluation().state() != FullInitializedInputs.State.SATISFIED) missing.add("initialized-input component");
            if (level.environmentAttributes() == null || level.getLevelData() == null) missing.add("world environment/clock context");
            if (legacyOwners == null || !legacyOwners.ready()) missing.add("prepared legacy-owner occurrences");
            if (serviceReservation == null) missing.add("finishing service reservation");
            var route = authority.directory().resolve(lifecycle.chunkKey()).orElse(null);
            if (inputReceipt() != null && inputReceipt().targets().stream().anyMatch(target ->
                    ((target.holder() != null && target.availability() != FullInitializedInputs.Availability.ABSENT)
                            || target.purposes().contains(FullInitializedInputs.Purpose.ENTITY_CONTEXT))
                            && !Objects.equals(route, target.route())))
                missing.add("regional enclosure convergence");
            return List.copyOf(missing);
        }
        public Optional<Prepared> prepared() {
            custody.gate().lock(); try { return Optional.ofNullable(prepared); } finally { custody.gate().unlock(); }
        }
        private void captureSavedInput() {
            if (input instanceof ImposterProtoChunk imposter) {
                if (imposter.getWrapped() instanceof FullSavedInput.PostLoadAccess access
                        && access.phoenium$postLoad() instanceof FullSavedInput.ReloadProcessor reload) savedInput = reload.input();
            } else if (input instanceof FullSavedInput.Access access) {
                savedInput = access.phoenium$savedInput();
                // This is inside real admitted FULL_PREPARE: every older raw generation writer
                // released and the original generation pins remain live. No unsynchronized live read.
                // Fact allocation is admitted separately by the input component after preparation.
            }
            if (savedInput != null) savedInput.activate();
        }
        public void requireFinishingAuthority(ChunkStageClaim claim, FullInitializedInputs.Receipt receipt) {
            claim.require(finishing.stage());
            if (serviceReservation == null) throw new ChunkProtocolException("FULL suffix has no service reservation");
            serviceReservation.requireActive(claim);
            if (receipt == null || receipt != claimedInputs || receipt != inputReceipt()
                    || validateInputs(finishing.stage(), receipt).state() != FullInitializedInputs.State.SATISFIED)
                throw new ChunkProtocolException("FULL suffix lacks its exact claimed input receipt");
            for (var target : receipt.targets()) if (target.route() != null
                    && (!target.route().region().equals(claim.frozenRoute().region())
                    || !target.route().generation().equals(claim.frozenRoute().generation())))
                throw new ChunkProtocolException("FULL input outside the claimed regional enclosure");
        }
        Set<RoutingKey> contextFootprint(List<FullInitializedInputs.Target> targets) {
            Set<RoutingKey> keys = new LinkedHashSet<>();
            keys.add(lifecycle.chunkKey());
            for (var target : targets) if ((target.holder() != null
                    && target.availability() != FullInitializedInputs.Availability.ABSENT)
                    || target.purposes().contains(FullInitializedInputs.Purpose.ENTITY_CONTEXT))
                keys.add(new RoutingKey(authority.world(), target.position().x(), target.position().z()));
            return Set.copyOf(keys);
        }
        /** Dependency refinement may converge prepared inputs before its final receipt exists.
         * It never waits or proposes topology while retaining an owner. */
        boolean encloseContextTargets(List<FullInitializedInputs.Target> targets) {
            requireObservationThread();
            if (dev.phoenium.ownership.OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
                throw new ChunkProtocolException("context enclosure requires an owner-free boundary");
            if (!inputActive()) return false;
            var keys = contextFootprint(targets);
            if (enclosure != null && !enclosure.enclose(keys)) return false;
            var route = authority.directory().resolve(lifecycle.chunkKey()).orElse(null);
            if (route == null) return false;
            for (var key : keys) if (!route.equals(authority.directory().resolve(key).orElse(null))
                    || authority.directory().pendingChange(key).filter(change -> !change.completed()).isPresent()) return false;
            return true;
        }
        /** Uses the eventual FULL lease but only its distinct dependency-read capability.
         * False requests owner-free rediscovery before the original can be claimed. */
        boolean validateContextInputs(FullContextReadClaim claim) {
            requireObservationThread();
            var receipt = claimedInputs;
            if (receipt == null || receipt != inputReceipt()) return false;
            for (var key : contextFootprint(receipt.targets())) claim.require(this, key);
            boolean current = receipt.contexts() == null || receipt.contexts().validateLive(claim, this);
            if (!current) inputs.changed();
            return current;
        }
        void prepareServices(ChunkStageKind kind) {
            if (kind != ChunkStageKind.FULL_FINISH || serviceReservation != null || !inputActive()
                    || inputEvaluation().state() != FullInitializedInputs.State.SATISFIED) return;
            // Actual context producer is mandatory. In-memory endpoints without a world context
            // continue to exercise the real missing-prerequisite branch.
            if (level.environmentAttributes() == null || level.getLevelData() == null) return;
            if (!encloseContextTargets(inputReceipt().targets())) return;
            var shared = FullFinishingServices.forServer(server);
            if (legacyOwners != null && legacyOwners.ready() && !legacyOwners.valid(finishing.stage(), savedSnapshot())) {
                legacyOwners.close(); legacyOwners = null;
            }
            if (legacyOwners == null) {
                legacyOwners = shared.legacyOwners.prepare(finishing.stage(), savedSnapshot());
                if (legacyOwners != null) legacyOwners.onReady(inputs::changed);
            }
            if (legacyOwners == null || !legacyOwners.ready()) return;
            var worlds = new ArrayList<ServerLevel>();
            worlds.add(level);
            for (var other : server.getAllLevels()) if (other != level) worlds.add(other);
            serviceReservation = shared.tryReserve(finishing.stage(), holder,
                    worlds, savedSnapshot().entities().size());
        }
        void stageExited(ChunkStageKind kind) {
            if (kind == ChunkStageKind.FULL_FINISH && serviceReservation != null) {
                serviceReservation.close(); serviceReservation = null; claimedInputs = null;
            }
        }
        boolean hasSavedInput() { return savedInput != null; }
        boolean savedAvailable() { return savedInput == null || savedInput.available(); }
        FullSavedInput.Snapshot captureSavedSnapshot() {
            if (savedInput == null) return null;
            // Listener capacity belongs to the admitted plan, not every graph waiting for capacity.
            savedInput.subscribe(savedChanged);
            var current = savedInput.snapshot();
            if (current != null) return current;
            if (input instanceof ImposterProtoChunk imposter) {
                var processor = ((FullSavedInput.PostLoadAccess) imposter.getWrapped()).phoenium$postLoad();
                if (!(processor instanceof FullSavedInput.ReloadProcessor reload) || reload.input() != savedInput) return null;
                return savedInput.capture(reload.entities());
            }
            return savedInput.capture(((ProtoChunk) input).getEntities());
        }
        void discardSavedSnapshot() {
            if (savedInput != null) { savedInput.unsubscribe(savedChanged); savedInput.discardSnapshot(); }
        }
        void inputEvidenceReleased() { lastInputEvaluation = null; }
        FullSavedInput.Snapshot savedSnapshot() { return savedInput == null ? null : savedInput.snapshot(); }
        boolean inputActive() {
            custody.gate().lock(); try { return stopped == null && !cleaned && custody.metadataEntitledLocked(work); }
            finally { custody.gate().unlock(); }
        }
        boolean inputRequested() { return inputRequested && finishingInputBound && inputActive(); }
        boolean inputsAcknowledged() { return !inputRequested; }
        public FullInitializedInputs.Evaluation inputEvaluation() { return inputs.inspect(this); }
        public FullInitializedInputs.Evaluation lastInputEvaluation() { return lastInputEvaluation; }
        public FullInitializedInputs.Receipt inputReceipt() { return inputs.receipt(this); }
        public List<FullInitializedInputs.Target> inputTargets() { return inputs.targets(this); }
        public FullInitializedInputs.Evaluation validateInputs(ChunkCustodyModel.StageId operation, FullInitializedInputs.Receipt receipt) {
            custody.gate().lock(); try { return inputs.evaluate(this, operation, receipt); } finally { custody.gate().unlock(); }
        }
        public void acknowledgeInputs() {
            inputRequested = false; lastInputEvaluation = null;
            if (savedInput != null) savedInput.unsubscribe(savedChanged);
            inputs.release(this);
        }
        /** Receipt-specific completion cannot close a newer discovery plan for this operation. */
        public void acknowledgeInputs(FullInitializedInputs.Receipt receipt) {
            requireObservationThread();
            if (receipt != null && inputs.receipt(this) == receipt) acknowledgeInputs();
        }
        public CompletableFuture<ChunkAccess> conversion() { return conversion; }
        public int preparedConsumers() { custody.gate().lock(); try { return consumers; } finally { custody.gate().unlock(); } }
        public CompletableFuture<ChunkAccess> returned() { return returned; }
        public CompletableFuture<ChunkResult<ChunkAccess>> adjacent() { return adjacent; }
        public AutoCloseable retainPrepared() {
            custody.gate().lock(); try {
                if (prepared == null || !inputActive()) throw new ChunkProtocolException("no current successful preparation receipt to retain");
                consumers++;
            } finally { custody.gate().unlock(); }
            return new AutoCloseable() {
                boolean closed;
                public void close() {
                    custody.gate().lock(); try { if (closed) return; closed = true; consumers--; } finally { custody.gate().unlock(); }
                    cleanup();
                }
            };
        }
        void wake() {
            boolean submit = false;
            boolean redeliver = false;
            ChunkStageAdmission.Result terminal = null;
            custody.gate().lock();
            try {
                if (cleaned) return;
                if (!custody.metadataEntitledLocked(work) && stopped == null)
                    terminal = custody.workState(work).orElse(null) == ChunkCustodyModel.WorkState.CANCELLED
                            ? ChunkStageAdmission.Result.CANCELLED : ChunkStageAdmission.Result.SUPERSEDED;
                else if (stopped == null && !queued && readinessLocked(ChunkStageKind.FULL_PREPARE) == LightInputService.Readiness.REGISTERED) {
                    queued = true; submit = preparation.bindInput(input);
                } else if (stopped == null && evidenceParked && readinessLocked(ChunkStageKind.FULL_PREPARE) == LightInputService.Readiness.REGISTERED) {
                    evidenceParked = false; redeliver = true;
                }
            } finally { custody.gate().unlock(); }
            if (terminal != null) stop(terminal, null);
            if (submit) pump.publishAndDeliver(preparation);
            if (redeliver) pump.deliverPublished(preparation);
            settle();
        }
        void awaitEvidence(ChunkStageKind kind) {
            if (kind == ChunkStageKind.FULL_FINISH) { finishingParked = true; return; }
            if (kind != ChunkStageKind.FULL_PREPARE) return;
            custody.gate().lock(); try { evidenceParked = true; } finally { custody.gate().unlock(); }
            wake();
        }
        boolean retryFinishing() {
            if (finishingParked && inputActive() && !finishing.outcome().isDone()
                    && (legacyOwners == null || legacyOwners.ready())) {
                finishingParked = false;
                pump.deliverPublished(finishing);
                return true;
            }
            return false;
        }
        private void preparedOutcome(ChunkStageOutcome<Prepared> outcome) {
            if (outcome instanceof ChunkStageOutcome.Applied<Prepared> applied) {
                finishing.bindInput(applied.value()); // Retained preparation; execution still requires all actual claims.
                finishingInputBound = true;
                inputs.changed();
            } else if (outcome instanceof ChunkStageOutcome.VanillaFailure<Prepared> failure) {
                conversion.completeExceptionally(failure.failure());
            } else if (outcome instanceof ChunkStageOutcome.BarrierFailure<Prepared> failure) {
                stop(ChunkStageAdmission.Result.FAILED_BARRIER, failure.failure());
            } else if (outcome instanceof ChunkStageOutcome.Control<Prepared> control) stop(control.result(), null);
            settle();
        }
        private void finishedOutcome(ChunkStageOutcome<ChunkAccess> outcome) {
            if (legacyOwners != null) { legacyOwners.close(); legacyOwners = null; }
            acknowledgeInputs();
            if (outcome instanceof ChunkStageOutcome.Applied<ChunkAccess> applied) {
                finished = true;
                if (stopped == null) conversion.complete(applied.value());
            } else if (outcome instanceof ChunkStageOutcome.VanillaFailure<ChunkAccess> failure)
                conversion.completeExceptionally(failure.failure());
            else if (outcome instanceof ChunkStageOutcome.BarrierFailure<ChunkAccess> failure)
                stop(ChunkStageAdmission.Result.FAILED_BARRIER, failure.failure());
            else if (outcome instanceof ChunkStageOutcome.Control<ChunkAccess> control) stop(control.result(), null);
            settle();
        }
        private void publishedOutcome(ChunkStageOutcome<ChunkResult<ChunkAccess>> outcome) {
            if (outcome instanceof ChunkStageOutcome.Applied<ChunkResult<ChunkAccess>> applied)
                publicationResult.complete(applied.value());
            else if (outcome instanceof ChunkStageOutcome.VanillaFailure<ChunkResult<ChunkAccess>> failure)
                publicationResult.completeExceptionally(failure.failure());
            else if (outcome instanceof ChunkStageOutcome.BarrierFailure<ChunkResult<ChunkAccess>> failure)
                stop(ChunkStageAdmission.Result.FAILED_BARRIER, failure.failure());
            else if (outcome instanceof ChunkStageOutcome.Control<ChunkResult<ChunkAccess>> control)
                stop(control.result(), null);
            published.admittedSettled(publicationEntry);
            settle();
        }
        void retainApplied(Object receipt) {
            Prepared result = (Prepared) receipt;
            custody.gate().lock(); try {
                if (result.holder() != holder || !result.work().equals(work) || !result.attempt().equals(preparation.stage()))
                    throw new ChunkProtocolException("successful preparation receipt lost its exact attempt");
                prepared = result; invocation.producer.generation.prepared = result.actual();
            } finally { custody.gate().unlock(); }
        }
        private void stop(ChunkStageAdmission.Result reason, Throwable cause) {
            if (reason != ChunkStageAdmission.Result.SHUTDOWN && reason != ChunkStageAdmission.Result.SUPERSEDED
                    && reason != ChunkStageAdmission.Result.CANCELLED && reason != ChunkStageAdmission.Result.FAILED_BARRIER)
                throw new ChunkProtocolException("nonterminal FULL control disposition");
            custody.gate().lock();
            try {
                if (stopped != null || cleaned) return;
                stopped = new Stopped(this, reason, cause);
                custody.retireWork(work, reason != ChunkStageAdmission.Result.SUPERSEDED);
            } finally { custody.gate().unlock(); }
            if (reason == ChunkStageAdmission.Result.FAILED_BARRIER) {
                custody.closeAdmissions();
                authority.barrier().trip(cause == null ? stopped : cause, authority.directory().snapshot().generation(),
                        new dev.phoenium.core.OwnerEpoch(0), null);
            }
            // An already claimed suffix still owns its saved/context/query inputs. Reentrant
            // shutdown must wait for that exact invocation's exit before acknowledging them.
            if (!custody.hasActiveUse(work)) acknowledgeInputs();
            preparation.control(reason); finishing.control(reason); publication.control(reason); settle();
        }
        private void settle() {
            Stopped terminal;
            custody.gate().lock(); try {
                if (custody.hasActiveUse(work)) return;
                terminal = stopped;
            } finally { custody.gate().unlock(); }
            if (terminal != null) {
                acknowledgeInputs();
                if (legacyOwners != null) { legacyOwners.close(); legacyOwners = null; }
                // Complete only this holder's original pending FULL slot, using vanilla's exact
                // failure-and-clear mechanism. A replacement holder's slot is never selected.
                var fullSlot = evidence.phoenium$futures().get(ChunkStatus.FULL.getIndex());
                if (fullSlot != null && !fullSlot.isDone()) evidence.phoenium$failAndClear(ChunkStatus.FULL.getIndex(), fullSlot);
                conversion.completeExceptionally(terminal);
                publicationResult.complete(GenerationChunkHolder.UNLOADED_CHUNK);
            }
            cleanup();
        }
        private void cleanup() {
            custody.gate().lock();
            try {
                if (cleaned || consumers != 0 || custody.hasActiveUse(work)
                        || !registrationSettled || !attached || !adjacent.isDone() || !returned.isDone() || !retention.isDone()
                        || !invocation.producer.finished || !invocation.producer.generation.writers.isEmpty()) return;
                cleaned = true; graphs.remove(this); returnedIndex.remove(returned, this);
                prepared = null; invocation.producer.generation.prepared = null;
                if (savedInput != null) { savedInput.unsubscribe(savedChanged); savedInput.discardSnapshot(); savedInput = null; }
                generations.remove(holder, invocation.producer.generation);
            } finally { custody.gate().unlock(); }
            published.admittedSettled(publicationEntry);
            if (stopped == null) services.settleFinishedExecution(work, holder);
            else services.releaseFinishedWork(work, holder);
            if (clock != null) clock.forget(this);
            cleanupGenerations();
        }
        void contextReadExited() {
            Stopped terminal;
            custody.gate().lock(); try { terminal = stopped; } finally { custody.gate().unlock(); }
            if (terminal != null) {
                preparation.control(terminal.reason); finishing.control(terminal.reason); publication.control(terminal.reason);
            }
            settle();
        }
    }
    public record FullResult(CompletableFuture<ChunkAccess> returned, ChunkAccess value, Throwable failure) { }
}
