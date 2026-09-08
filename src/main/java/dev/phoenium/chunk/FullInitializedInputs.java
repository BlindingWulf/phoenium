package dev.phoenium.chunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;

/** Direct initialized-input discovery and retained evidence. This class creates no execution claim.
 * Metadata uses the existing custody gate. Discovery/demand and all acknowledgements occur outside it.
 * A single coalesced producer notification drives bounded passes; no neighboring FULL future is awaited.
 */
public final class FullInitializedInputs {
    public enum State { SATISFIED, PENDING, STALE, TERMINAL, UNRESOLVED, CAPACITY }
    public enum Purpose { SELF_INITIALIZED, DIFFICULTY, COLLISION, DYNAMIC_SHAPE_MUTATION, ENVIRONMENT, BLOCK_CONTEXT, ENTITY_CONTEXT }
    public enum Availability { PUBLISHED, PREPARED, BIOMES, ROUTED, PREPARATION_PENDING, ABSENT, STALE, TOPOLOGY_PENDING, TERMINAL, UNROUTABLE, CAPACITY }
    public record Limits(int plans, int targetsPerPlan, int edges, int consumers, int factBytes) {
        public Limits { if (plans < 1 || targetsPerPlan < 1 || edges < 1 || consumers < 1 || factBytes < FullSavedInput.MAX_FACT_BYTES) throw new IllegalArgumentException("finite input limits and at least one fact allocation required"); }
        public static Limits production() { return new Limits(256, 256, 8192, 8192, 16 * 1024 * 1024); }
    }
    public record Target(ChunkPos position, Set<Purpose> purposes, Availability availability,
                         GenerationChunkHolder holder, ChunkCustodyModel.LifecycleId lifecycle,
                         ChunkCustodyModel.WorkId work, ChunkCustodyModel.StageId preparationAttempt,
                         LevelChunk actual, CompletableFuture<ChunkResult<ChunkAccess>> lowerSlot,
                         CompletableFuture<ChunkResult<ChunkAccess>> publicSlot, CompletableFuture<Void> generationRetention,
                         ChunkStatus reservation, RegionRoute route, int ticketLevel,
                         FullPublishedInputs.Evidence publication, FullBiomeInputs.Evidence biomes) {
        public Target { purposes = Set.copyOf(purposes); }
    }
    public record Receipt(Object server, ServerLevel world, ChunkCustodyModel.WorkId source,
                          ChunkCustodyModel.StageId operation, long plan, long savedRevision,
                          RegionDirectory.View routes, List<Target> targets, FullSavedContextInputs.Plan contexts) {
        public Receipt(Object server, ServerLevel world, ChunkCustodyModel.WorkId source,
                       ChunkCustodyModel.StageId operation, long plan, long savedRevision,
                       RegionDirectory.View routes, List<Target> targets) {
            this(server, world, source, operation, plan, savedRevision, routes, targets, null);
        }
        public Receipt { targets = List.copyOf(targets); }
    }
    public record Evaluation(State state, String obligation, Receipt receipt) { }
    private static final class Plan {
        final FullRegistrationRuntime.Graph graph;
        final FullSavedInput.Snapshot saved;
        final long sequence;
        final RegionDirectory.View routes;
        List<Target> targets = List.of();
        final List<AutoCloseable> pins = new ArrayList<>();
        Receipt receipt;
        FullSavedContextInputs.Plan contexts;
        long factCharge;
        Evaluation evaluation = new Evaluation(State.PENDING, "direct preparation availability", null);
        Plan(FullRegistrationRuntime.Graph graph, FullSavedInput.Snapshot saved, long sequence, RegionDirectory.View routes) {
            this.graph = graph; this.saved = saved; this.sequence = sequence; this.routes = routes;
            factCharge = Math.max(FullSavedInput.MAX_FACT_BYTES, saved.payloadBytes());
        }
    }
    private final FullRegistrationRuntime runtime;
    private final Limits limits;
    private final Map<FullRegistrationRuntime.Graph, Plan> plans = new LinkedHashMap<>();
    private final Set<GenerationChunkHolder> requested = Collections.newSetFromMap(new IdentityHashMap<>());
    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean();
    private long nextPlan, validations;
    private long edges, consumers, bytes;
    /** Extra retention is bounded by one finite operation and expires on acknowledgement.
     * This is workload-proportional accounting, not a fixed Java heap-byte guarantee. */
    private Plan expanded;
    FullInitializedInputs(FullRegistrationRuntime runtime, Limits limits) { this.runtime = runtime; this.limits = limits; }
    public void changed() { dirty.set(true); }

    boolean pump() {
        if (!dirty.getAndSet(false)) return false;
        runtime.requireObservationThread();
        requested.removeIf(holder -> runtime.visible(holder.getPos()) != holder || runtime.findGraph(holder).isPresent()
                || holder.getChunkIfPresent(ChunkStatus.BIOMES) != null);
        boolean progress = false;
        for (var graph : runtime.graphSnapshot()) {
            if (!graph.inputRequested()) { release(graph); continue; }
            if (graph.prepared().isEmpty()) continue;
            if (!graph.savedAvailable()) continue;
            Plan prior = plans.get(graph);
            if (prior != null && stale(prior)) { release(graph); prior = null; }
            if (prior != null && prior.receipt != null) { progress |= graph.retryFinishing(); continue; }
            if (prior == null) {
                if (!graph.hasSavedInput()) continue;
                if (expanded != null || plans.size() >= limits.plans || bytes + FullSavedInput.MAX_FACT_BYTES > limits.factBytes) continue;
                var saved = graph.captureSavedSnapshot();
                if (saved == null) continue;
                prior = new Plan(graph, saved, nextPlan++, runtime.authority.directory().snapshot());
                plans.put(graph, prior); bytes += prior.factCharge;
                if (prior.factCharge > FullSavedInput.MAX_FACT_BYTES) expanded = prior;
            }
            bind(prior);
            progress = true;
        }
        return progress;
    }
    private void bind(Plan plan) {
        Map<ChunkPos, Set<Purpose>> required = new LinkedHashMap<>();
        add(required, plan.graph.holder.getPos(), Purpose.SELF_INITIALIZED);
        String unmet = plan.saved.unmet();
        if (unmet == null && plan.contexts == null) {
            try { plan.contexts = FullSavedContextInputs.prepare(runtime.level(), runtime.authority.world(),
                    runtime.authority.directory(), plan.graph.finishing.stage(), plan.saved, this::changed,
                    contextBytes -> reserveContexts(plan, contextBytes)); }
            catch (ChunkProtocolException bound) { unmet = bound.getMessage(); }
        }
        if (unmet == null && plan.contexts != null) unmet = plan.contexts.unmet();
        if (unmet == null && plan.contexts != null && plan.contexts.refinementRetry()) {
            plan.contexts.close(); plan.contexts = null;
            plan.evaluation = new Evaluation(State.PENDING, "owner-published context dependency recapture", null);
            changed(); return;
        }
        if (unmet == null && plan.contexts != null && plan.contexts.refinementCapacity()) {
            plan.contexts.close(); plan.contexts = null;
            plan.evaluation = new Evaluation(State.CAPACITY, "expanded operation acknowledgement", null); return;
        }
        if (plan.contexts != null) {
            long charge = Math.max(FullSavedInput.MAX_FACT_BYTES,
                    Math.addExact(plan.saved.payloadBytes(), plan.contexts.payloadBytes()));
            if (charge > plan.factCharge && !expand(plan)) {
                plan.contexts.close(); plan.contexts = null;
                plan.evaluation = new Evaluation(State.CAPACITY, "expanded operation acknowledgement", null); return;
            }
            bytes += charge - plan.factCharge; plan.factCharge = charge;
            for (BlockPos block : plan.contexts.blocks())
                add(required, new ChunkPos(block.getX() >> 4, block.getZ() >> 4), Purpose.BLOCK_CONTEXT);
            for (ChunkPos position : plan.contexts.selectorChunks())
                add(required, position, Purpose.ENTITY_CONTEXT);
            for (ChunkPos position : plan.contexts.predicateBiomeChunks())
                add(required, position, Purpose.ENVIRONMENT);
            for (ChunkPos position : plan.contexts.predicateFullChunks())
                add(required, position, Purpose.BLOCK_CONTEXT);
        }
        for (var fact : plan.saved.entities()) {
            if (!Double.isFinite(fact.x()) || !Double.isFinite(fact.y()) || !Double.isFinite(fact.z())) {
                unmet = fact.path() + ": invalid saved position retains original load failure; no input closure"; break;
            }
            if (FullSavedVillagerInputs.supports(fact)) {
                try {
                    for (ChunkPos position : FullSavedVillagerInputs.environmentPositions(fact))
                        add(required, position, Purpose.ENVIRONMENT);
                } catch (IllegalArgumentException invalid) { unmet = invalid.getMessage(); break; }
                continue;
            }
            if (FullSavedEntityInputs.supports(fact)) continue;
            if (fact.type().equals("minecraft:text_display")) {
                continue;
            }
            if (!fact.type().equals("minecraft:tadpole")) { unmet = fact.path() + ": concrete reader inputs unbound for " + fact.type(); break; }
            if (FullSavedInput.converts(fact)) {
                add(required, new ChunkPos(((int) Math.floor(fact.x())) >> 4, ((int) Math.floor(fact.z())) >> 4), Purpose.DIFFICULTY);
                for (ChunkPos position : FullSavedEntityInputs.conversionBiomePositions(fact))
                    add(required, position, Purpose.ENVIRONMENT);
                Set<ChunkPos> collision;
                try { collision = FullSavedInput.collisionPositions(fact, Integer.MAX_VALUE); }
                catch (IllegalArgumentException bound) { unmet = bound.getMessage(); break; }
                for (ChunkPos position : collision) {
                    add(required, position, Purpose.COLLISION);
                    // Audited shapes may request FULL,true and IMMEDIATE creation at these positions.
                    // Identifying this potential mutation never grants its ticket/world/enclosure turn.
                    add(required, position, Purpose.DYNAMIC_SHAPE_MUTATION);
                }
            }
        }
        if (unmet != null) { plan.evaluation = new Evaluation(State.UNRESOLVED, unmet, null); return; }
        if ((required.size() > limits.targetsPerPlan || required.size() > limits.edges) && !expand(plan)) {
            plan.evaluation = new Evaluation(State.CAPACITY, "expanded operation acknowledgement", null); return;
        }
        long otherExpandedEdges = expanded != null && expanded != plan ? expanded.targets.size() : 0;
        if (expanded != plan && edges - otherExpandedEdges - plan.targets.size() + required.size() > limits.edges) {
            plan.evaluation = new Evaluation(State.CAPACITY, "input edge capacity release", null); return;
        }
        var targets = new ArrayList<Target>();
        for (var entry : required.entrySet()) targets.add(resolve(plan, entry.getKey(), entry.getValue(), required.size()));
        edges += targets.size() - plan.targets.size(); plan.targets = List.copyOf(targets);
        for (var target : targets) {
            switch (target.availability) {
                case PUBLISHED -> {
                    if (target.publication == null) {
                        plan.evaluation = new Evaluation(State.UNRESOLVED, "already-published input at " + target.position + " lacks original publication evidence (including tracking capacity refusal)", null); return;
                    }
                }
                case PREPARATION_PENDING -> { plan.evaluation = new Evaluation(State.PENDING, "original reservation/lower-generation producer then admitted preparation at " + target.position, null); return; }
                case CAPACITY -> { plan.evaluation = new Evaluation(State.CAPACITY, "original input demand capacity release at " + target.position, null); return; }
                case TOPOLOGY_PENDING -> { plan.evaluation = new Evaluation(State.PENDING, "actual topology completion at " + target.position, null); return; }
                case UNROUTABLE -> { plan.evaluation = new Evaluation(State.UNRESOLVED, "permanently unroutable active input demand at " + target.position, null); return; }
                case STALE -> { plan.evaluation = new Evaluation(State.STALE, "holder/work/object replaced at " + target.position, null); return; }
                case TERMINAL -> { plan.evaluation = new Evaluation(State.TERMINAL, "authentic preparation terminal at " + target.position, null); return; }
                default -> { }
            }
        }
        if (plan.contexts != null && plan.contexts.refineBlocks(targets)) {
            plan.evaluation = plan.contexts.unmet() != null
                    ? new Evaluation(State.UNRESOLVED, plan.contexts.unmet(), null)
                    : plan.contexts.refinementCapacity()
                    ? new Evaluation(State.CAPACITY, "expanded operation acknowledgement", null)
                    : new Evaluation(State.PENDING, "protected block context dependency refinement", null);
            if (plan.contexts.refinementCapacity()) { plan.contexts.close(); plan.contexts = null; }
            else if (!plan.contexts.refinementWaiting()) changed();
            return;
        }
        RefinementTargets refinement = plan.contexts != null && plan.contexts.needsLiveRefinement()
                ? retainRefinementTargets(plan, targets) : new RefinementTargets(List.of(), false, false);
        if (refinement.capacity || refinement.pending) {
            plan.evaluation = new Evaluation(refinement.capacity ? State.CAPACITY : State.PENDING,
                    refinement.capacity ? "live context input consumer acknowledgement" : "live context target retention", null); return;
        }
        boolean refinedLive;
        Throwable refinementFailure = null;
        try { refinedLive = plan.contexts != null && plan.contexts.refineLive(runtime, plan.graph, targets); }
        catch (Throwable failure) { refinementFailure = failure; refinedLive = false; }
        try { releaseRefinementTargets(refinement.pins); }
        catch (Throwable cleanup) {
            if (refinementFailure == null) refinementFailure = cleanup;
            else if (refinementFailure != cleanup) refinementFailure.addSuppressed(cleanup);
        }
        if (refinementFailure instanceof Error error) throw error;
        if (refinementFailure instanceof RuntimeException failure) throw failure;
        if (refinementFailure != null) throw new ChunkProtocolException("live context refinement failed", refinementFailure);
        if (refinedLive) {
            plan.evaluation = plan.contexts.unmet() != null
                    ? new Evaluation(State.UNRESOLVED, plan.contexts.unmet(), null)
                    : plan.contexts.refinementCapacity()
                    ? new Evaluation(State.CAPACITY, "expanded operation acknowledgement", null)
                    : new Evaluation(State.PENDING, "protected live component dependency refinement", null);
            if (plan.contexts.refinementCapacity()) { plan.contexts.close(); plan.contexts = null; }
            else if (!plan.contexts.refinementWaiting()) changed();
            return;
        }
        int needed = (int) targets.stream().filter(t -> t.availability == Availability.PREPARED || t.availability == Availability.PUBLISHED || t.availability == Availability.BIOMES).count();
        if (needed > limits.consumers && !expand(plan)) {
            plan.evaluation = new Evaluation(State.CAPACITY, "expanded operation acknowledgement", null); return;
        }
        long otherExpandedConsumers = expanded != null && expanded != plan ? expanded.pins.size() : 0;
        if (expanded != plan && consumers - otherExpandedConsumers + needed > limits.consumers) {
            plan.evaluation = new Evaluation(State.CAPACITY, "independent input consumer acknowledgement", null); return;
        }
        // All target preparations have settled before reserving capacity or retaining any target.
        for (var target : targets) if (target.availability == Availability.PREPARED || target.availability == Availability.PUBLISHED || target.availability == Availability.BIOMES) {
            AutoCloseable pin = target.availability == Availability.PUBLISHED ? runtime.published.retain(target.publication)
                    : target.availability == Availability.BIOMES ? FullBiomeInputs.retain(runtime, target)
                    : runtime.findGraph(target.holder).orElseThrow().retainPrepared();
            if (pin == null) { release(plan.graph); changed(); return; }
            if (plans.get(plan.graph) != plan || !plan.graph.inputRequested()) {
                try { pin.close(); } catch (Exception failure) { throw new ChunkProtocolException("abandoned input acquisition", failure); }
                release(plan.graph); return;
            }
            plan.pins.add(pin); consumers++;
        }
        if (!plan.graph.inputRequested() || plans.get(plan.graph) != plan || stale(plan)) { release(plan.graph); changed(); return; }
        plan.receipt = new Receipt(runtime.server(), runtime.level(), plan.graph.work, plan.graph.finishing.stage(),
                plan.sequence, plan.saved.revision(), plan.routes, plan.targets, plan.contexts);
        plan.evaluation = new Evaluation(State.SATISFIED, "exact operation's retained initialized inputs", plan.receipt);
        // Ownership and receipt are installed before this synchronous prerequisite-consumer notification.
        runtime.pump.publishAndDeliver(plan.graph.finishing);
    }
    private static void add(Map<ChunkPos, Set<Purpose>> required, ChunkPos position, Purpose purpose) {
        required.computeIfAbsent(position, ignored -> EnumSet.noneOf(Purpose.class)).add(purpose);
    }
    private boolean expand(Plan plan) {
        if (expanded != null && expanded != plan) return false;
        expanded = plan; return true;
    }
    private boolean reserveContexts(Plan plan, long contextBytes) {
        long charge = Math.max(FullSavedInput.MAX_FACT_BYTES,
                Math.addExact(plan.saved.payloadBytes(), contextBytes));
        if (charge <= plan.factCharge) return true;
        if (!expand(plan)) return false;
        bytes = Math.addExact(bytes, charge - plan.factCharge); plan.factCharge = charge;
        return true;
    }
    /** Target evidence must survive both nonblocking owner-read phases. These temporary uses
     * are charged like final consumers and independently acknowledged before new demand. */
    private record RefinementTargets(List<AutoCloseable> pins, boolean capacity, boolean pending) { }
    private RefinementTargets retainRefinementTargets(Plan plan, List<Target> targets) {
        var eligible = targets.stream().filter(target -> target.availability == Availability.PREPARED
                || target.availability == Availability.PUBLISHED || target.availability == Availability.BIOMES).toList();
        if (eligible.size() > limits.consumers && !expand(plan)) return new RefinementTargets(List.of(), true, false);
        long otherExpanded = expanded != null && expanded != plan ? expanded.pins.size() : 0;
        if (expanded != plan && consumers - otherExpanded + eligible.size() > limits.consumers)
            return new RefinementTargets(List.of(), true, false);
        var retained = new ArrayList<AutoCloseable>();
        try {
            for (var target : eligible) {
                AutoCloseable pin = target.availability == Availability.PUBLISHED ? runtime.published.retain(target.publication)
                        : target.availability == Availability.BIOMES ? FullBiomeInputs.retain(runtime, target)
                        : runtime.findGraph(target.holder).map(FullRegistrationRuntime.Graph::retainPrepared).orElse(null);
                if (pin == null) {
                    var abandoned = List.copyOf(retained); retained.clear(); releaseRefinementTargets(abandoned);
                    changed(); return new RefinementTargets(List.of(), false, true);
                }
                retained.add(pin); consumers++;
                if (plans.get(plan.graph) != plan || !plan.graph.inputRequested() || stale(plan)) {
                    var abandoned = List.copyOf(retained); retained.clear(); releaseRefinementTargets(abandoned);
                    release(plan.graph); changed();
                    return new RefinementTargets(List.of(), false, true);
                }
            }
        } catch (Throwable failure) {
            try { releaseRefinementTargets(retained); }
            catch (Throwable cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new ChunkProtocolException("live context target acquisition failed", failure);
        }
        return new RefinementTargets(List.copyOf(retained), false, false);
    }
    private void releaseRefinementTargets(List<AutoCloseable> retained) {
        var failures = new FullInputNotifications.Failures();
        for (AutoCloseable pin : retained) failures.run(() -> {
            try { pin.close(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new ChunkProtocolException("live context target acknowledgement failed", failure); }
            finally { consumers--; }
        });
        failures.rethrow();
    }
    private Target resolve(Plan plan, ChunkPos position, Set<Purpose> purposes, int footprint) {
        FullRegistrationRuntime.Graph source = plan.graph;
        long demandCapacity = (long) limits.edges + (expanded == plan ? footprint : 0);
        ChunkHolder holder = runtime.visible(position);
        var key = new RoutingKey(runtime.authority.world(), position.x(), position.z());
        RegionRoute route = runtime.authority.directory().resolve(key).orElse(null);
        int ticket = holder == null ? Integer.MAX_VALUE : holder.getTicketLevel();
        if (purposes.equals(Set.of(Purpose.ENTITY_CONTEXT))) {
            if (runtime.authority.directory().pendingChange(key).filter(change -> !change.completed()).isPresent())
                return target(position, purposes, Availability.TOPOLOGY_PENDING, null, null, null, route, ticket);
            return target(position, purposes, route == null ? Availability.UNROUTABLE : Availability.ROUTED,
                    null, null, null, route, ticket);
        }
        if (purposes.contains(Purpose.ENVIRONMENT)
                && purposes.stream().allMatch(purpose -> purpose == Purpose.ENVIRONMENT || purpose == Purpose.ENTITY_CONTEXT)) {
            if (holder == null || ticket > ChunkLevel.byStatus(ChunkStatus.BIOMES))
                return target(position, purposes, Availability.ABSENT, holder, null, null, route, ticket);
            if (route == null) return target(position, purposes,
                    runtime.authority.directory().pendingChange(key).isPresent() ? Availability.TOPOLOGY_PENDING : Availability.UNROUTABLE,
                    holder, null, null, route, ticket);
            var biome = FullBiomeInputs.capture(holder);
            if (biome != null && runtime.biomesReleased(holder))
                return target(position, purposes, Availability.BIOMES, holder, null, null, route, ticket);
            if (requested.size() >= demandCapacity && !requested.contains(holder))
                return target(position, purposes, Availability.CAPACITY, holder, null, null, route, ticket);
            if (requested.add(holder)) {
                holder.scheduleChunkGenerationTask(ChunkStatus.BIOMES, runtime.level().getChunkSource().chunkMap)
                        .whenComplete((ignored, failure) -> changed());
                runtime.level().getChunkSource().chunkMap.runGenerationTasks();
            }
            return target(position, purposes, Availability.PREPARATION_PENDING, holder, null, null, route, ticket);
        }
        if (position.equals(source.holder.getPos()) && holder != source.holder)
            return target(position, purposes, Availability.STALE, holder, null, null, route, ticket);
        if (holder == null || ticket > ChunkLevel.byStatus(ChunkStatus.FULL))
            return target(position, purposes, Availability.ABSENT, holder, null, null, route, ticket);
        if (runtime.authority.directory().pendingChange(key).filter(change -> !change.completed()).isPresent())
            return target(position, purposes, Availability.TOPOLOGY_PENDING, holder, null, null, route, ticket);
        if (route == null) return target(position, purposes, Availability.UNROUTABLE, holder, null, null, null, ticket);
        var full = ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex());
        if (full != null && full.isDone() && !full.isCompletedExceptionally()) {
            var value = full.getNow(null);
            if (value != null && (value.orElse(null) instanceof LevelChunk || value.orElse(null) instanceof ImposterProtoChunk)) {
                ChunkAccess original = value.orElse(null);
                LevelChunk actual = original instanceof LevelChunk fullChunk ? fullChunk : ((ImposterProtoChunk) original).getWrapped();
                return target(position, purposes, Availability.PUBLISHED, holder, null, actual, route, ticket);
            }
        }
        var graph = runtime.findGraph(holder).orElse(null);
        if (graph == null) {
            if (!requested.contains(holder) && requested.size() >= demandCapacity)
                return target(position, purposes, Availability.CAPACITY, holder, null, null, route, ticket);
            if (requested.size() < demandCapacity && requested.add(holder)) {
                // Exactly the original FULL,false holder policy: no new ticket and no invented reservation.
                // Observe preparation events from the registrar; never subscribe to this public FULL future.
                holder.scheduleChunkGenerationTask(ChunkStatus.FULL, runtime.level().getChunkSource().chunkMap);
                runtime.level().getChunkSource().chunkMap.runGenerationTasks();
            }
            return target(position, purposes, Availability.PREPARATION_PENDING, holder, null, null, route, ticket);
        }
        if (!graph.inputActive()) return target(position, purposes, Availability.TERMINAL, holder, graph, null, route, ticket);
        var prepared = graph.prepared().orElse(null);
        if (prepared == null && graph.preparation.outcome().isDone())
            return target(position, purposes, Availability.TERMINAL, holder, graph, null, route, ticket);
        if (prepared == null) return target(position, purposes, Availability.PREPARATION_PENDING, holder, graph, null, route, ticket);
        var lower = holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN);
        if (!(lower instanceof ImposterProtoChunk imposter) || imposter.getWrapped() != prepared.actual())
            return target(position, purposes, Availability.STALE, holder, graph, null, route, ticket);
        if ((purposes.contains(Purpose.DIFFICULTY) || purposes.contains(Purpose.COLLISION)
                || purposes.contains(Purpose.DYNAMIC_SHAPE_MUTATION))
                && ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex()) == null) {
            // The original FULL,false demand creates its public subscriber slot before the
            // operation receipt is captured. No slot is fabricated and no FULL result is awaited.
            holder.scheduleChunkGenerationTask(ChunkStatus.FULL, runtime.level().getChunkSource().chunkMap);
        }
        return target(position, purposes, Availability.PREPARED, holder, graph, prepared.actual(), route, ticket);
    }
    private Target target(ChunkPos position, Set<Purpose> purposes, Availability availability, GenerationChunkHolder holder,
                          FullRegistrationRuntime.Graph graph, LevelChunk actual, RegionRoute route, int ticket) {
        FullHolderEvidence evidence = holder == null ? null : (FullHolderEvidence) holder;
        var publication = availability == Availability.PUBLISHED ? runtime.published.observe(holder) : null;
        return new Target(position, purposes, availability, holder, publication != null ? publication.lifecycle() : graph == null ? null : graph.lifecycle,
                publication != null ? publication.work() : graph == null ? null : graph.work, graph == null ? null : graph.preparation.stage(), actual,
                evidence == null ? null : evidence.phoenium$futures().get(ChunkStatus.SPAWN.getIndex()),
                evidence == null ? null : evidence.phoenium$futures().get(ChunkStatus.FULL.getIndex()),
                evidence == null ? null : evidence.phoenium$generationSaveSync(),
                evidence == null ? null : evidence.phoenium$startedWork().get(), route, ticket, publication,
                holder == null || !purposes.contains(Purpose.ENVIRONMENT) ? null : FullBiomeInputs.capture(holder));
    }
    private boolean stale(Plan plan) {
        if (plan.graph.savedSnapshot() != plan.saved || runtime.authority.directory().snapshot() != plan.routes) return true;
        if (plan.contexts != null && !plan.contexts.valid(plan.saved)) return true;
        for (Target target : plan.targets) {
            if (target.availability == Availability.ROUTED) {
                var key = new RoutingKey(runtime.authority.world(), target.position.x(), target.position.z());
                if (!Objects.equals(target.route, runtime.authority.directory().resolve(key).orElse(null))
                        || runtime.authority.directory().pendingChange(key).filter(c -> !c.completed()).isPresent()) return true;
                continue;
            }
            var holder = runtime.visible(target.position);
            if (holder != target.holder || holder != null && holder.getTicketLevel() != target.ticketLevel) return true;
            if (holder == null) continue;
            if (target.availability == Availability.BIOMES && !FullBiomeInputs.validates(holder, target.biomes)) return true;
            var evidence = (FullHolderEvidence) holder;
            if (target.availability == Availability.PREPARED || target.availability == Availability.PUBLISHED) {
                if (target.availability == Availability.PUBLISHED) {
                    if (target.publication != null && !runtime.published.validates(target.publication)) return true;
                    // Published pins may create a new generation-save incarnation after the producer settled.
                    if (target.publicSlot != evidence.phoenium$futures().get(ChunkStatus.FULL.getIndex())
                            || target.lowerSlot != evidence.phoenium$futures().get(ChunkStatus.SPAWN.getIndex())) return true;
                } else {
                    if (target.reservation != evidence.phoenium$startedWork().get()
                            || target.lowerSlot != evidence.phoenium$futures().get(ChunkStatus.SPAWN.getIndex())
                            || target.publicSlot != evidence.phoenium$futures().get(ChunkStatus.FULL.getIndex())
                            || target.generationRetention != evidence.phoenium$generationSaveSync()) return true;
                    var graph = runtime.findGraph(holder).orElse(null);
                    if (graph == null || !graph.inputActive() || !graph.work.equals(target.work)
                            || graph.prepared().map(p -> p.actual() != target.actual || !p.attempt().equals(target.preparationAttempt)).orElse(true)) return true;
                }
            }
            var key = new RoutingKey(runtime.authority.world(), target.position.x(), target.position.z());
            if (target.availability != Availability.TOPOLOGY_PENDING
                    && runtime.authority.directory().pendingChange(key).filter(c -> !c.completed()).isPresent()) return true;
        }
        return false;
    }
    /** Actual FULL_FINISH prerequisite check, inside the existing admission fence, before any claim. */
    Evaluation evaluate(FullRegistrationRuntime.Graph graph, ChunkCustodyModel.StageId operation, Receipt supplied) {
        runtime.requireObservationThread(); validations++;
        if (runtime.level().getServer() != runtime.server()) return new Evaluation(State.STALE, "source server instance replaced", null);
        if (!graph.inputActive()) return new Evaluation(State.TERMINAL, "source work retired", null);
        if (graph.inputsAcknowledged()) return new Evaluation(State.TERMINAL, "operation input consumer acknowledged", null);
        if (!graph.savedAvailable()) return new Evaluation(State.PENDING, "saved input writer release", null);
        Plan plan = plans.get(graph);
        if (plan == null) return new Evaluation(!graph.hasSavedInput() ? State.UNRESOLVED : State.CAPACITY,
                !graph.hasSavedInput() ? "retained reload saved-input producer is unbound" : "input plan/payload capacity release", null);
        if (supplied != plan.receipt || !graph.finishing.stage().equals(operation))
            return new Evaluation(State.STALE, "receipt does not belong to the exact current operation/attempt", null);
        if (stale(plan)) { changed(); return new Evaluation(State.STALE, "saved input, holder, slot, lifecycle or routing changed; rediscovery required", null); }
        if (plan.receipt != null && (plan.receipt.server != runtime.server() || plan.receipt.world != runtime.level()
                || !plan.receipt.source.equals(graph.work) || plan.receipt.plan != plan.sequence))
            return new Evaluation(State.STALE, "foreign input evidence", null);
        return plan.evaluation;
    }
    Evaluation inspect(FullRegistrationRuntime.Graph graph) {
        Plan plan = plans.get(graph);
        return evaluate(graph, graph.finishing.stage(), plan == null ? null : plan.receipt);
    }
    Receipt receipt(FullRegistrationRuntime.Graph graph) { Plan plan = plans.get(graph); return plan == null ? null : plan.receipt; }
    List<Target> targets(FullRegistrationRuntime.Graph graph) { Plan plan = plans.get(graph); return plan == null ? List.of() : plan.targets; }
    void release(FullRegistrationRuntime.Graph graph) {
        Plan plan = plans.remove(graph);
        if (plan == null) return;
        graph.inputEvidenceReleased();
        edges -= plan.targets.size(); bytes -= plan.factCharge;
        if (expanded == plan) expanded = null;
        graph.discardSavedSnapshot();
        var pins = List.copyOf(plan.pins); consumers -= pins.size();
        plan.pins.clear(); plan.targets = List.of(); plan.receipt = null;
        var contexts = plan.contexts; plan.contexts = null;
        Throwable failure = null;
        try {
            if (contexts != null) try { contexts.close(); } catch (Throwable problem) { failure = problem; }
            for (var pin : pins) try { pin.close(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            boolean active = runtime.graphSnapshot().stream().anyMatch(g -> g.inputActive());
            requested.removeIf(holder -> runtime.findGraph(holder).isPresent() || !active);
        } finally { changed(); }
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new ChunkProtocolException("input acknowledgement failed", failure);
    }
    public Map<String, Long> measurements() {
        return Map.of("plans", (long) plans.size(), "edges", (long) edges, "consumers", (long) consumers,
                "factBytes", (long) bytes, "pendingNotifications", dirty.get() ? 1L : 0L,
                "requestedPreparations", (long) requested.size(), "validations", validations,
                "expandedOperations", expanded == null ? 0L : 1L);
    }
}
