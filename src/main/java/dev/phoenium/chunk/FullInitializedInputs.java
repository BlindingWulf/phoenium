package dev.phoenium.chunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    public enum Purpose { SELF_INITIALIZED, DIFFICULTY, COLLISION, DYNAMIC_SHAPE_MUTATION }
    public enum Availability { PUBLISHED, PREPARED, PREPARATION_PENDING, ABSENT, STALE, TOPOLOGY_PENDING, TERMINAL, UNROUTABLE, CAPACITY }
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
                         FullPublishedInputs.Evidence publication) {
        public Target { purposes = Set.copyOf(purposes); }
    }
    public record Receipt(Object server, ServerLevel world, ChunkCustodyModel.WorkId source,
                          ChunkCustodyModel.StageId operation, long plan, long savedRevision,
                          RegionDirectory.View routes, List<Target> targets) {
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
        Evaluation evaluation = new Evaluation(State.PENDING, "direct preparation availability", null);
        Plan(FullRegistrationRuntime.Graph graph, FullSavedInput.Snapshot saved, long sequence, RegionDirectory.View routes) {
            this.graph = graph; this.saved = saved; this.sequence = sequence; this.routes = routes;
        }
    }
    private final FullRegistrationRuntime runtime;
    private final Limits limits;
    private final Map<FullRegistrationRuntime.Graph, Plan> plans = new LinkedHashMap<>();
    private final Set<GenerationChunkHolder> requested = Collections.newSetFromMap(new IdentityHashMap<>());
    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean();
    private long nextPlan, validations;
    private int edges, consumers, bytes;
    FullInitializedInputs(FullRegistrationRuntime runtime, Limits limits) { this.runtime = runtime; this.limits = limits; }
    public void changed() { dirty.set(true); }

    boolean pump() {
        if (!dirty.getAndSet(false)) return false;
        runtime.requireObservationThread();
        requested.removeIf(holder -> runtime.visible(holder.getPos()) != holder || runtime.findGraph(holder).isPresent());
        boolean progress = false;
        for (var graph : runtime.graphSnapshot()) {
            if (!graph.inputRequested()) { release(graph); continue; }
            if (graph.prepared().isEmpty()) continue;
            if (!graph.savedAvailable()) continue;
            Plan prior = plans.get(graph);
            if (prior != null && stale(prior)) { release(graph); prior = null; }
            if (prior != null && prior.receipt != null) continue;
            if (prior == null) {
                if (!graph.hasSavedInput()) continue;
                if (plans.size() >= limits.plans || bytes + FullSavedInput.MAX_FACT_BYTES > limits.factBytes) continue;
                var saved = graph.captureSavedSnapshot();
                if (saved == null) continue;
                prior = new Plan(graph, saved, nextPlan++, runtime.authority.directory().snapshot());
                plans.put(graph, prior); bytes += FullSavedInput.MAX_FACT_BYTES;
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
        for (var fact : plan.saved.entities()) {
            if (!fact.type().equals("minecraft:tadpole")) { unmet = fact.path() + ": concrete reader inputs unbound for " + fact.type(); break; }
            if (fact.contextualDimensions()) { unmet = fact.path() + ": saved attributes/effects/pose dimension context unbound"; break; }
            if (!Double.isFinite(fact.x()) || !Double.isFinite(fact.y()) || !Double.isFinite(fact.z())) {
                unmet = fact.path() + ": invalid saved position retains original load failure; no input closure"; break;
            }
            if (FullSavedInput.converts(fact)) {
                add(required, new ChunkPos(((int) Math.floor(fact.x())) >> 4, ((int) Math.floor(fact.z())) >> 4), Purpose.DIFFICULTY);
                Set<ChunkPos> collision;
                try { collision = FullSavedInput.collisionPositions(fact, limits.targetsPerPlan); }
                catch (IllegalArgumentException bound) { unmet = bound.getMessage(); break; }
                for (ChunkPos position : collision) {
                    add(required, position, Purpose.COLLISION);
                    // Audited shapes may request FULL,true and IMMEDIATE creation at these positions.
                    // Identifying this potential mutation never grants its ticket/world/enclosure turn.
                    add(required, position, Purpose.DYNAMIC_SHAPE_MUTATION);
                }
                if (required.size() > limits.targetsPerPlan) { unmet = "direct footprint exceeds per-operation input bound"; break; }
            }
        }
        if (unmet != null) { plan.evaluation = new Evaluation(State.UNRESOLVED, unmet, null); return; }
        if (required.size() > limits.targetsPerPlan) {
            plan.evaluation = new Evaluation(State.UNRESOLVED, "direct footprint exceeds per-operation input bound", null); return;
        }
        if (required.size() > limits.edges) {
            plan.evaluation = new Evaluation(State.UNRESOLVED, "operation cannot fit configured input edge capacity", null); return;
        }
        if (edges - plan.targets.size() + required.size() > limits.edges) {
            plan.evaluation = new Evaluation(State.CAPACITY, "input edge capacity release", null); return;
        }
        var targets = new ArrayList<Target>();
        for (var entry : required.entrySet()) targets.add(resolve(plan.graph, entry.getKey(), entry.getValue()));
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
        int needed = (int) targets.stream().filter(t -> t.availability == Availability.PREPARED || t.availability == Availability.PUBLISHED).count();
        if (needed > limits.consumers) {
            plan.evaluation = new Evaluation(State.UNRESOLVED, "operation cannot fit configured input consumer capacity", null); return;
        }
        if (consumers + needed > limits.consumers) {
            plan.evaluation = new Evaluation(State.CAPACITY, "independent input consumer acknowledgement", null); return;
        }
        // All target preparations have settled before reserving capacity or retaining any target.
        for (var target : targets) if (target.availability == Availability.PREPARED || target.availability == Availability.PUBLISHED) {
            AutoCloseable pin = target.availability == Availability.PUBLISHED ? runtime.published.retain(target.publication)
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
                plan.sequence, plan.saved.revision(), plan.routes, plan.targets);
        plan.evaluation = new Evaluation(State.SATISFIED, "exact operation's retained initialized inputs", plan.receipt);
        // Ownership and receipt are installed before this synchronous prerequisite-consumer notification.
        runtime.pump.publishAndDeliver(plan.graph.finishing);
    }
    private static void add(Map<ChunkPos, Set<Purpose>> required, ChunkPos position, Purpose purpose) {
        required.computeIfAbsent(position, ignored -> EnumSet.noneOf(Purpose.class)).add(purpose);
    }
    private Target resolve(FullRegistrationRuntime.Graph source, ChunkPos position, Set<Purpose> purposes) {
        ChunkHolder holder = runtime.visible(position);
        var key = new RoutingKey(runtime.authority.world(), position.x(), position.z());
        RegionRoute route = runtime.authority.directory().resolve(key).orElse(null);
        int ticket = holder == null ? Integer.MAX_VALUE : holder.getTicketLevel();
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
            if (!requested.contains(holder) && requested.size() >= limits.edges)
                return target(position, purposes, Availability.CAPACITY, holder, null, null, route, ticket);
            if (requested.size() < limits.edges && requested.add(holder)) {
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
                evidence == null ? null : evidence.phoenium$startedWork().get(), route, ticket, publication);
    }
    private boolean stale(Plan plan) {
        if (plan.graph.savedSnapshot() != plan.saved || runtime.authority.directory().snapshot() != plan.routes) return true;
        for (Target target : plan.targets) {
            var holder = runtime.visible(target.position);
            if (holder != target.holder || holder != null && holder.getTicketLevel() != target.ticketLevel) return true;
            if (holder == null) continue;
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
        edges -= plan.targets.size(); bytes -= FullSavedInput.MAX_FACT_BYTES;
        graph.discardSavedSnapshot();
        var pins = List.copyOf(plan.pins); consumers -= pins.size();
        plan.pins.clear(); plan.targets = List.of(); plan.receipt = null;
        for (var pin : pins) try { pin.close(); } catch (Exception failure) { throw new ChunkProtocolException("input acknowledgement failed", failure); }
        requested.removeIf(holder -> runtime.findGraph(holder).isPresent() || !runtime.graphSnapshot().stream().anyMatch(g -> g.inputActive()));
        changed();
    }
    public Map<String, Long> measurements() {
        return Map.of("plans", (long) plans.size(), "edges", (long) edges, "consumers", (long) consumers,
                "factBytes", (long) bytes, "pendingNotifications", dirty.get() ? 1L : 0L,
                "requestedPreparations", (long) requested.size(), "validations", validations);
    }
}
