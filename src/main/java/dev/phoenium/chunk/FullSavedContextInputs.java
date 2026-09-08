package dev.phoenium.chunk;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RoutingKey;
import java.util.*;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.ValueInput;

/** Protected context discovery and exact saved-reader consumption. Discovery may read named
 * saved-data services before R; it never constructs an entity or evaluates its selector,
 * predicate, reader, brain, or gameplay random source. Original resolution runs once later.
 */
public final class FullSavedContextInputs {
    private static final ThreadLocal<Plan> ACTIVE = new ThreadLocal<>();
    private FullSavedContextInputs() { }

    public static Plan prepare(ServerLevel world, WorldId worldId, RegionDirectory directory,
                               ChunkCustodyModel.StageId stage, FullSavedInput.Snapshot saved, Runnable changed,
                               java.util.function.LongPredicate capacity) {
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
            throw new ChunkProtocolException("saved context preparation requires owner-free discovery");
        Plan plan = new Plan(world, worldId, directory, stage, saved, changed, capacity);
        try {
            for (var fact : saved.entities()) if (fact.type().equals("minecraft:text_display")) plan.discover(fact);
            return plan;
        } catch (RuntimeException | Error failure) { plan.close(); throw failure; }
    }
    private record Pending(FullSavedInput.EntityFact fact, FullSavedTextInputs.NbtRead read) { }
    private record LiveContribution(Pending pending, FullSavedTextInputs.Requirements requirements) { }
    private sealed interface LiveReceipt permits EntityLiveReceipt, BlockLiveReceipt {
        boolean valid(FullContextReadClaim claim, FullRegistrationRuntime.Graph graph);
    }
    private record EntityLiveReceipt(Entity alias, FullEntityFacts.Fact fact, RoutingKey position,
                                     FullLiveComponentInputs.Field field,
                                     FullLiveComponentInputs.Snapshot snapshot) implements LiveReceipt {
        @Override public boolean valid(FullContextReadClaim claim, FullRegistrationRuntime.Graph graph) {
            claim.require(graph, position);
            return alias instanceof FullEntityFacts.Access access && access.phoenium$referenceFact() == fact
                    && snapshot.valid(FullLiveComponentInputs.entity(alias, field));
        }
    }
    private record BlockLiveReceipt(net.minecraft.world.level.chunk.ChunkAccess chunk, BlockPos position,
                                    BlockEntity block, FullLiveComponentInputs.BlockSnapshot snapshot,
                                    RoutingKey route) implements LiveReceipt {
        @Override public boolean valid(FullContextReadClaim claim, FullRegistrationRuntime.Graph graph) {
            claim.require(graph, route);
            BlockEntity current = FullLiveComponentInputs.block(chunk, position);
            return current == block && snapshot.valid(current);
        }
    }
    private record LiveObservation(List<LiveReceipt> receipts, List<LiveContribution> contributions,
                                   long bytes, String unmet, boolean grew) { }
    @FunctionalInterface public interface SelectorCall<T> { T call() throws CommandSyntaxException; }

    public static final class Plan implements AutoCloseable {
        private static final long ENTITY_CANDIDATE_BYTES = 192L;
        private final ServerLevel world;
        private final WorldId worldId;
        private final RegionDirectory directory;
        private final ChunkCustodyModel.StageId stage;
        private final FullSavedInput.Snapshot saved;
        private final net.minecraft.core.RegistryAccess registry;
        private final Map<String, List<FullSavedTextInputs.Requirements>> texts = new LinkedHashMap<>();
        private final Map<Identifier, FullCommandStorageInputs.Capture> storage = new LinkedHashMap<>();
        private final List<Pending> pendingBlocks = new ArrayList<>();
        private final Set<Pending> refinedBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<Pending> pendingEntities = new ArrayList<>();
        private final List<Pending> pendingLiveBlocks = new ArrayList<>();
        private final Set<Pending> refinedEntities = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Pending> refinedLiveBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<LiveReceipt> liveReceipts = new ArrayList<>();
        private final Map<BlockPos, FullBlockEntityTagInputs.Capture> blockTags = new HashMap<>();
        private final Map<BlockPos, FullBlockEntityTagInputs.Absence> blockMapAbsences = new HashMap<>();
        private final Map<BlockPos, FullBlockEntityTagInputs.Absence> blockReloadAbsences = new HashMap<>();
        private final Map<BlockPos, AutoCloseable> blockWaiters = new HashMap<>();
        private final List<AutoCloseable> consumers = new ArrayList<>();
        private final Runnable changed;
        private final java.util.function.LongPredicate capacity;
        private FullEntityFacts.Cut entityCut;
        private Set<net.minecraft.world.level.ChunkPos> selectorChunks = Set.of();
        private final Set<net.minecraft.world.level.ChunkPos> predicateBiomeChunks = new LinkedHashSet<>();
        private final Set<net.minecraft.world.level.ChunkPos> predicateFullChunks = new LinkedHashSet<>();
        private final Set<net.minecraft.world.level.ChunkPos> predicateStructureChunks = new LinkedHashSet<>();
        private net.minecraft.server.ReloadableServerRegistries.Holder predicateRegistries;
        private final Set<net.minecraft.advancements.predicates.entity.EntityPredicate> entityPredicates =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private int maximumSelectorCandidates;
        private IdentityHashMap<Entity, FullEntityFacts.Fact> selectorCandidates;
        private String unmet;
        private long bytes;
        private ChunkStageClaim claim;
        private String currentText;
        private boolean refinementWaiting;
        private boolean refinementCapacity;
        private boolean refinementRetry;
        private boolean closed;
        private Plan(ServerLevel world, WorldId worldId, RegionDirectory directory, ChunkCustodyModel.StageId stage,
                     FullSavedInput.Snapshot saved, Runnable changed, java.util.function.LongPredicate capacity) {
            this.world = world; this.worldId = worldId; this.directory = directory;
            this.stage = stage; this.saved = saved; this.changed = changed; this.capacity = capacity;
            registry = world.registryAccess();
        }
        public String unmet() { return unmet; }
        long payloadBytes() { return bytes; }
        int blockTagCaptures() { return blockTags.size(); }
        boolean refinementWaiting() { return refinementWaiting; }
        boolean refinementCapacity() { return refinementCapacity; }
        boolean refinementRetry() { return refinementRetry; }
        boolean needsLiveRefinement() {
            return pendingEntities.stream().anyMatch(pending -> !refinedEntities.contains(pending))
                    || pendingLiveBlocks.stream().anyMatch(pending -> !refinedLiveBlocks.contains(pending));
        }
        public boolean valid(FullSavedInput.Snapshot current) {
            var facts = FullFinishingServices.forServer(world.getServer()).entityFacts;
            var reservation = claim == null ? null : FullFinishingServices.currentReservation(world.getServer());
            return !closed && !refinementRetry && current == saved && world.registryAccess() == registry
                    && (predicateRegistries == null || world.getServer().reloadableRegistries() == predicateRegistries)
                    && storage.values().stream().allMatch(FullCommandStorageInputs.Capture::valid)
                    && blockTags.values().stream().allMatch(FullBlockEntityTagInputs.Capture::valid)
                    && blockMapAbsences.values().stream().allMatch(FullBlockEntityTagInputs.Absence::valid)
                    && blockReloadAbsences.values().stream().allMatch(FullBlockEntityTagInputs.Absence::valid)
                    && (entityCut == null || facts.valid(entityCut, reservation, maximumSelectorCandidates, selectorChunks));
        }
        List<FullSavedTextInputs.Requirements> requirements(String path) { return List.copyOf(texts.getOrDefault(path, List.of())); }
        Set<BlockPos> blocks() {
            var result = new LinkedHashSet<BlockPos>();
            for (var requirements : texts.values()) result.addAll(requirements.stream().flatMap(r -> r.blocks().stream()).toList());
            return Collections.unmodifiableSet(result);
        }
        Set<net.minecraft.world.level.ChunkPos> selectorChunks() {
            return selectorChunks;
        }
        Set<net.minecraft.world.level.ChunkPos> predicateBiomeChunks() { return Collections.unmodifiableSet(predicateBiomeChunks); }
        Set<net.minecraft.world.level.ChunkPos> predicateFullChunks() { return Collections.unmodifiableSet(predicateFullChunks); }
        Set<net.minecraft.world.level.ChunkPos> predicateStructureChunks() { return Collections.unmodifiableSet(predicateStructureChunks); }
        private void discover(FullSavedInput.EntityFact fact) {
            var queue = new ArrayDeque<Pending>();
            add(fact, FullSavedTextInputs.discover(fact, world.registryAccess()), queue);
            process(queue);
        }
        private void process(ArrayDeque<Pending> queue) {
            while (!queue.isEmpty() && unmet == null) {
                Pending next = queue.remove(); var fact = next.fact; var read = next.read;
                if (read.source() != FullSavedTextInputs.Source.STORAGE) {
                    if (read.source() == FullSavedTextInputs.Source.BLOCK) {
                        if (read.interpreting() && read.recursionDepth() <= 100) pendingBlocks.add(next);
                        continue;
                    }
                    if (read.source() == FullSavedTextInputs.Source.ENTITY) {
                        var selector = declaredSelector(fact.path(), read.target(), FullSavedTextInputs.Use.NBT);
                        var liveField = FullLiveComponentInputs.entityField(read.path());
                        if (selector != null && !read.interpreting() && (selector.self() || selector.worldLimited())) continue;
                        if (selector != null && selector.self() && read.interpreting() && read.recursionDepth() > 100)
                            continue;
                        if (selector != null && selector.worldLimited() && read.interpreting() && read.recursionDepth() > 100)
                            continue;
                        if (selector != null && selector.self() && read.interpreting()
                                && liveField == FullLiveComponentInputs.Field.CUSTOM_NAME) {
                            FullSavedTextInputs.decode(fact.services().customName(), world.registryAccess()).ifPresent(component ->
                                    add(fact, FullSavedTextInputs.discoverInterpreted(fact, component, read.recursionDepth()), queue));
                            continue;
                        }
                        if (selector != null && selector.self() && read.interpreting()
                                && liveField == FullLiveComponentInputs.Field.DISPLAY_TEXT
                                && fact.type().equals("minecraft:text_display")) {
                            FullSavedTextInputs.decode(fact.services().text(), world.registryAccess()).ifPresent(component ->
                                    add(fact, FullSavedTextInputs.discoverInterpreted(fact, component, read.recursionDepth()), queue));
                            continue;
                        }
                        if (selector != null && selector.self() && read.interpreting()
                                && liveField == FullLiveComponentInputs.Field.MANNEQUIN_DESCRIPTION
                                && fact.type().equals("minecraft:mannequin")) {
                            FullSavedTextInputs.decode(fact.services().description(), world.registryAccess()).ifPresent(component ->
                                    add(fact, FullSavedTextInputs.discoverInterpreted(fact, component, read.recursionDepth()), queue));
                            continue;
                        }
                        if (selector != null && selector.worldLimited() && read.interpreting() && liveField != null) {
                            pendingEntities.add(next);
                            for (var savedCandidate : saved.entities()) {
                                var source = liveField == FullLiveComponentInputs.Field.CUSTOM_NAME ? savedCandidate.services().customName()
                                        : liveField == FullLiveComponentInputs.Field.DISPLAY_TEXT
                                        && savedCandidate.type().equals("minecraft:text_display") ? savedCandidate.services().text()
                                        : liveField == FullLiveComponentInputs.Field.MANNEQUIN_DESCRIPTION
                                        && savedCandidate.type().equals("minecraft:mannequin") ? savedCandidate.services().description()
                                        : FullSavedTextInputs.Saved.EMPTY;
                                FullSavedTextInputs.decode(source, world.registryAccess()).ifPresent(component ->
                                        add(fact, FullSavedTextInputs.discoverInterpreted(fact, component, read.recursionDepth()), queue));
                            }
                            continue;
                        }
                    }
                    unmet = fact.path() + ": protected display " + read.source() + " context producer pending"; break;
                }
                Identifier id = Identifier.parse(read.target());
                var capture = storage.get(id);
                if (capture == null) {
                    try { capture = FullCommandStorageInputs.observe(world.getServer(), id, this::reserve); }
                    catch (FullCommandStorageInputs.Capacity capacity) { refinementCapacity = true; return; }
                    bytes = Math.addExact(bytes, capture.bytes);
                    storage.put(id, capture);
                    consumers.add(capture.retain(changed));
                }
                if (!read.interpreting() || read.recursionDepth() > 100) continue;
                try {
                    var path = new NbtPathArgument().parse(new StringReader(read.path()));
                    for (var value : path.get(capture.detached)) {
                        var decoded = ComponentSerialization.CODEC.parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), value).result();
                        if (decoded.isPresent()) add(fact, FullSavedTextInputs.discoverInterpreted(fact, decoded.get(), read.recursionDepth()), queue);
                    }
                } catch (CommandSyntaxException absent) {
                    // Original NbtContents treats a missing path as an empty stream.
                }
            }
        }
        /** Called only after the exact BLOCK_CONTEXT preparation has settled. Reads the guarded
         * pending-tag producer, never the chunk's live block entities or serializer. */
        boolean refineBlocks(List<FullInitializedInputs.Target> targets) {
            boolean changedPlan = false;
            refinementWaiting = false; refinementCapacity = false;
            var byPosition = new HashMap<net.minecraft.world.level.ChunkPos, FullInitializedInputs.Target>();
            for (var target : targets) byPosition.put(target.position(), target);
            for (int index = 0; index < pendingBlocks.size() && unmet == null; index++) {
                Pending pending = pendingBlocks.get(index);
                if (refinedBlocks.contains(pending)) continue;
                var read = pending.read;
                var target = byPosition.get(new net.minecraft.world.level.ChunkPos(read.block().getX() >> 4, read.block().getZ() >> 4));
                if (target == null || target.actual() == null) {
                    refinedBlocks.add(pending); changedPlan = true;
                    continue; // Original isLoaded false/absent branch.
                }
                var capture = blockTags.get(read.block());
                if (capture == null) {
                    try {
                        if (!blockMapAbsences.containsKey(read.block())) {
                            var observation = FullBlockEntityTagInputs.observeVersioned(target.actual(), read.block(),
                                    world.registryAccess(), changed, this::reserve);
                            capture = observation.capture;
                            if (observation.absence != null) {
                                blockMapAbsences.put(read.block(), observation.absence);
                                consumers.add(observation.absence.retain(changed));
                            }
                        }
                        if (capture == null && !blockReloadAbsences.containsKey(read.block())
                                && target.actual() instanceof FullSavedInput.PostLoadAccess access
                                && access.phoenium$postLoad() instanceof FullSavedInput.ReloadProcessor reload) {
                            var observation = reload.blockEntities().observeVersioned(target.actual().getPos(), read.block(),
                                    world.registryAccess(), changed, this::reserve);
                            capture = observation.capture;
                            if (observation.absence != null) {
                                blockReloadAbsences.put(read.block(), observation.absence);
                                consumers.add(observation.absence.retain(changed));
                            }
                        }
                    } catch (FullBlockEntityTagInputs.Retry retry) {
                        AutoCloseable prior = blockWaiters.put(read.block(), retry.waiter);
                        if (prior != null) close(prior, "replaced pending block dependency waiter");
                        refinementWaiting = true; changedPlan = true;
                        continue;
                    } catch (FullBlockEntityTagInputs.Capacity capacity) {
                        refinementCapacity = true; changedPlan = true;
                        continue;
                    }
                    AutoCloseable waiter = blockWaiters.remove(read.block());
                    if (waiter != null) close(waiter, "completed pending block dependency waiter");
                    if (capture != null) {
                        blockTags.put(read.block(), capture); bytes = Math.addExact(bytes, capture.bytes());
                        consumers.add(capture.retain(changed));
                    }
                }
                if (capture == null) {
                    refinedBlocks.add(pending); changedPlan = true;
                    pendingLiveBlocks.add(pending);
                    continue;
                }
                if (FullLiveComponentInputs.blockField(read.path()) != FullLiveComponentInputs.BlockField.CUSTOM_NAME) {
                    // The serialized tag is only the discovery receipt.  For audited command
                    // and sign fields, the later live refinement must consume the original
                    // lazy block instance and its serializer.  Keep every other path an
                    // explicit unmet producer rather than treating arbitrary saved keys as
                    // covered by the detached tag.
                    if (FullLiveComponentInputs.blockField(read.path()) == null) {
                        unmet = pending.fact.path() + ": pending block-entity interpreted path producer pending for " + read.path();
                        break;
                    }
                    List<FullSavedTextInputs.Saved> components;
                    try { components = capture.components(read.path(), this::reserve, changed); }
                    catch (FullBlockEntityTagInputs.Retry retry) {
                        // Keep the exact capture in valid(): a source change must rebuild
                        // and acknowledge the whole plan, including every old retention.
                        // A merely busy observer can retry this same valid capture later.
                        AutoCloseable prior = blockWaiters.put(read.block(), retry.waiter);
                        if (prior != null) close(prior, "replaced pending block dependency waiter");
                        refinementWaiting = true; changedPlan = true; continue;
                    }
                    catch (FullBlockEntityTagInputs.Capacity capacity) {
                        refinementCapacity = true; changedPlan = true; continue;
                    }
                    for (var component : components) bytes = Math.addExact(bytes, component.payloadBytes());
                    refinedBlocks.add(pending); changedPlan = true;
                    pendingLiveBlocks.add(pending);
                    var queue = new ArrayDeque<Pending>();
                    for (var component : components) FullSavedTextInputs.decode(component, registry).ifPresent(value ->
                            add(pending.fact, FullSavedTextInputs.discoverInterpreted(pending.fact, value,
                                    read.recursionDepth()), queue));
                    process(queue);
                    continue;
                }
                refinedBlocks.add(pending); changedPlan = true;
                var queue = new ArrayDeque<Pending>();
                FullSavedTextInputs.decode(capture.customName(), world.registryAccess()).ifPresent(component ->
                        add(pending.fact, FullSavedTextInputs.discoverInterpreted(pending.fact, component, read.recursionDepth()), queue));
                process(queue);
            }
            return changedPlan;
        }
        /** Refines audited live component fields under their exact same-world owner. The first
         * pass measures only scalar graph sizes; capacity is reserved before the second pass
         * detaches syntax and structural receipts. */
        boolean refineLive(FullRegistrationRuntime runtime, FullRegistrationRuntime.Graph graph,
                           List<FullInitializedInputs.Target> targets) {
            List<Pending> entities = pendingEntities.stream().filter(pending -> !refinedEntities.contains(pending)).toList();
            List<Pending> blocks = pendingLiveBlocks.stream().filter(pending -> !refinedLiveBlocks.contains(pending)).toList();
            if (entities.isEmpty() && blocks.isEmpty()) return false;
            refinementWaiting = false; refinementCapacity = false;
            if (!graph.encloseContextTargets(targets)) { refinementWaiting = true; return true; }
            var expected = directory.resolve(graph.lifecycle.chunkKey()).orElse(null);
            if (expected == null) { refinementRetry = true; return true; }
            Set<RoutingKey> footprint = graph.contextFootprint(targets);
            var byPosition = new HashMap<net.minecraft.world.level.ChunkPos, FullInitializedInputs.Target>();
            for (var target : targets) byPosition.put(target.position(), target);
            var measured = FullContextRefinement.read(runtime, graph, expected, footprint,
                    claim -> observeLive(runtime, claim, graph, entities, blocks, byPosition, -1L, false));
            if (measured.state() != FullContextRefinement.State.OBSERVED) {
                refinementWaiting = measured.state() == FullContextRefinement.State.PENDING;
                refinementRetry = measured.state() == FullContextRefinement.State.STALE;
                return true;
            }
            LiveObservation size = measured.value();
            if (size.grew()) { refinementRetry = true; return true; }
            if (size.unmet() != null) { unmet = size.unmet(); return true; }
            if (!reserve(size.bytes())) { refinementCapacity = true; return true; }
            var captured = FullContextRefinement.read(runtime, graph, expected, footprint,
                    claim -> observeLive(runtime, claim, graph, entities, blocks, byPosition, size.bytes(), true));
            if (captured.state() != FullContextRefinement.State.OBSERVED) {
                refinementWaiting = captured.state() == FullContextRefinement.State.PENDING;
                refinementRetry = captured.state() == FullContextRefinement.State.STALE;
                return true;
            }
            LiveObservation observation = captured.value();
            if (observation.grew()) { refinementRetry = true; return true; }
            if (observation.unmet() != null) { unmet = observation.unmet(); return true; }
            bytes = Math.addExact(bytes, size.bytes()); liveReceipts.addAll(observation.receipts());
            var queue = new ArrayDeque<Pending>();
            for (LiveContribution contribution : observation.contributions()) {
                add(contribution.pending.fact, contribution.requirements(), queue);
            }
            refinedEntities.addAll(entities); refinedLiveBlocks.addAll(blocks); process(queue);
            return true;
        }

        private LiveObservation observeLive(FullRegistrationRuntime runtime, FullContextReadClaim claim,
                                            FullRegistrationRuntime.Graph graph,
                                            List<Pending> entities, List<Pending> blocks,
                                            Map<net.minecraft.world.level.ChunkPos, FullInitializedInputs.Target> targets,
                                            long allowance, boolean capture) {
            long total = 0; var receipts = new ArrayList<LiveReceipt>(); var contributions = new ArrayList<LiveContribution>();
            if (!entities.isEmpty() && entityCut != null
                    && !FullFinishingServices.forServer(world.getServer()).entityFacts.valid(entityCut))
                return new LiveObservation(List.of(), List.of(), 0L, null, true);
            if (!entities.isEmpty() && entityCut != null) for (Pending pending : entities) {
                FullLiveComponentInputs.Field field = FullLiveComponentInputs.entityField(pending.read.path());
                if (field == null) return new LiveObservation(List.of(), List.of(), total,
                        pending.fact.path() + ": live entity interpreted path producer pending for " + pending.read.path(), false);
                for (var candidate : entityCut.candidates()) {
                    if (field == FullLiveComponentInputs.Field.DISPLAY_TEXT
                            && candidate.fact().type() != net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY) continue;
                    if (field == FullLiveComponentInputs.Field.MANNEQUIN_DESCRIPTION
                            && candidate.fact().type() != net.minecraft.world.entity.EntityTypes.MANNEQUIN) continue;
                    if (field == FullLiveComponentInputs.Field.MANNEQUIN_DESCRIPTION
                            && candidate.fact().dispatch() != net.minecraft.world.entity.decoration.Mannequin.class)
                        return new LiveObservation(List.of(), List.of(), total,
                                pending.fact.path() + ": mannequin description producer has an unaudited virtual override "
                                        + candidate.fact().dispatch().getName(), false);
                    RoutingKey key = new RoutingKey(worldId, candidate.position().x(), candidate.position().z()); claim.require(graph, key);
                    if (!(candidate.alias() instanceof FullEntityFacts.Access access)
                            || access.phoenium$referenceFact() != candidate.fact())
                        return new LiveObservation(List.of(), List.of(), total, null, true);
                    Component component = FullLiveComponentInputs.entity(candidate.alias(), field);
                    var measure = FullLiveComponentInputs.measure(component, pending.read.recursionDepth());
                    if (measure.unmet() != null) return new LiveObservation(List.of(), List.of(), total,
                            pending.fact.path() + ": " + measure.unmet(), false);
                    total = Math.addExact(total, measure.bytes());
                    if (capture) {
                        var snapshot = FullLiveComponentInputs.capture(pending.fact, component, pending.read.recursionDepth(),
                                Math.max(0L, Math.subtractExact(allowance, Math.subtractExact(total, measure.bytes()))));
                        if (snapshot.unmet() != null && snapshot.unmet().contains("grew after"))
                            return new LiveObservation(List.of(), List.of(), total, null, true);
                        if (snapshot.unmet() != null) return new LiveObservation(List.of(), List.of(), total,
                                pending.fact.path() + ": " + snapshot.unmet(), false);
                        receipts.add(new EntityLiveReceipt(candidate.alias(), candidate.fact(), key, field, snapshot));
                        contributions.add(new LiveContribution(pending, snapshot.requirements()));
                    }
                }
            }
            for (Pending pending : blocks) {
                var position = pending.read.block(); var chunkPosition = new net.minecraft.world.level.ChunkPos(position.getX() >> 4, position.getZ() >> 4);
                var target = targets.get(chunkPosition);
                if (target == null || target.actual() == null) continue;
                RoutingKey key = new RoutingKey(worldId, chunkPosition.x(), chunkPosition.z()); claim.require(graph, key);
                if (!validLiveTarget(runtime, target))
                    return new LiveObservation(List.of(), List.of(), total, null, true);
                BlockEntity block = FullLiveComponentInputs.block(target.actual(), position);
                if (FullNbtPathInputs.cachedCustomName(pending.read.path())) {
                    // EncoderCache is process-global. Other original vanilla serializers can
                    // begin an equal-key load after same-lease validation and before this
                    // FULL's original getUnchecked call. Guava would then wait while R is held.
                    // No current custody fence covers all of those producers, so this exact
                    // cached field stays explicit rather than inferring safety from a snapshot.
                    return new LiveObservation(List.of(), List.of(), total,
                            pending.fact.path() + ": cached DataComponents custom_name context is unsupported because "
                                    + "another vanilla encoder can begin an equal-key load before the original serializer", false);
                }
                FullLiveComponentInputs.BlockField field = FullLiveComponentInputs.blockField(pending.read.path());
                if (field == null)
                    return new LiveObservation(List.of(), List.of(), total,
                            pending.fact.path() + ": live block-entity interpreted path producer pending for " + pending.read.path(), false);
                var measure = FullLiveComponentInputs.measureBlock(block, field, pending.read.recursionDepth());
                if (measure.unmet() != null) return new LiveObservation(List.of(), List.of(), total,
                        pending.fact.path() + ": " + measure.unmet(), false);
                total = Math.addExact(total, measure.bytes());
                if (capture) {
                    var snapshot = FullLiveComponentInputs.captureBlock(pending.fact, block, field, pending.read.recursionDepth(),
                            Math.max(0L, Math.subtractExact(allowance, Math.subtractExact(total, measure.bytes()))));
                    if (snapshot.unmet() != null && snapshot.unmet().contains("grew after"))
                        return new LiveObservation(List.of(), List.of(), total, null, true);
                    if (snapshot.unmet() != null) return new LiveObservation(List.of(), List.of(), total,
                            pending.fact.path() + ": " + snapshot.unmet(), false);
                    receipts.add(new BlockLiveReceipt(target.actual(), position, block, snapshot, key));
                    for (var requirement : snapshot.requirements()) contributions.add(new LiveContribution(pending, requirement));
                }
            }
            return new LiveObservation(List.copyOf(receipts), List.copyOf(contributions), total, null, capture && total > allowance);
        }

        private static boolean validLiveTarget(FullRegistrationRuntime runtime, FullInitializedInputs.Target target) {
            if (runtime.visible(target.position()) != target.holder() || target.holder() == null
                    || target.holder().getTicketLevel() != target.ticketLevel()) return false;
            var evidence = (FullHolderEvidence) target.holder();
            if (target.availability() == FullInitializedInputs.Availability.PUBLISHED) {
                if (target.publication() == null || !runtime.published.validates(target.publication())
                        || target.publicSlot() != evidence.phoenium$futures().get(net.minecraft.world.level.chunk.status.ChunkStatus.FULL.getIndex())
                        || target.lowerSlot() != evidence.phoenium$futures().get(net.minecraft.world.level.chunk.status.ChunkStatus.SPAWN.getIndex())) return false;
                var result = target.publicSlot().getNow(null); Object current = result == null ? null : result.orElse(null);
                var actual = current instanceof net.minecraft.world.level.chunk.LevelChunk level ? level
                        : current instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter ? imposter.getWrapped() : null;
                return actual == target.actual();
            }
            if (target.availability() != FullInitializedInputs.Availability.PREPARED) return false;
            var targetGraph = runtime.findGraph(target.holder()).orElse(null);
            return targetGraph != null && targetGraph.inputActive() && targetGraph.lifecycle.equals(target.lifecycle())
                    && targetGraph.work.equals(target.work())
                    && target.reservation() == evidence.phoenium$startedWork().get()
                    && target.lowerSlot() == evidence.phoenium$futures().get(net.minecraft.world.level.chunk.status.ChunkStatus.SPAWN.getIndex())
                    && target.publicSlot() == evidence.phoenium$futures().get(net.minecraft.world.level.chunk.status.ChunkStatus.FULL.getIndex())
                    && target.generationRetention() == evidence.phoenium$generationSaveSync()
                    && targetGraph.prepared().map(prepared -> prepared.actual() == target.actual()
                    && prepared.attempt().equals(target.preparationAttempt())).orElse(false);
        }

        boolean validateLive(FullContextReadClaim claim, FullRegistrationRuntime.Graph graph) {
            if (closed || refinementRetry) return false;
            for (LiveReceipt receipt : liveReceipts) if (!receipt.valid(claim, graph)) {
                refinementRetry = true; changed.run(); return false;
            }
            return true;
        }
        private boolean reserve(long addition) { return capacity.test(Math.addExact(bytes, addition)); }
        private static void close(AutoCloseable value, String message) {
            try { value.close(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new ChunkProtocolException(message, failure); }
        }
        private void add(FullSavedInput.EntityFact fact, FullSavedTextInputs.Requirements requirements, ArrayDeque<Pending> queue) {
            texts.computeIfAbsent(fact.path(), ignored -> new ArrayList<>()).add(requirements);
            if (requirements.unmet() != null) { unmet = requirements.unmet(); return; }
            for (var selector : requirements.selectors()) {
                boolean ownedSelf = selector.self();
                boolean identityScore = selector.use() == FullSavedTextInputs.Use.SCORE && !selector.source().startsWith("@");
                if (!ownedSelf && !identityScore && !selector.worldLimited()) {
                    unmet = fact.path() + ": protected display selector context producer pending for " + selector.source(); return;
                }
                if (!ownedSelf && !identityScore && entityCut == null) {
                    var facts = FullFinishingServices.forServer(world.getServer()).entityFacts;
                    var estimate = facts.estimate(world);
                    int conversions = (int) saved.entities().stream().filter(FullSavedInput::converts).count();
                    maximumSelectorCandidates = Math.addExact(estimate.candidates(), Math.addExact(saved.entities().size(), conversions));
                    long charge = Math.multiplyExact(ENTITY_CANDIDATE_BYTES, maximumSelectorCandidates);
                    if (!reserve(charge)) { refinementCapacity = true; return; }
                    entityCut = facts.observe(world, estimate);
                    if (entityCut == null) { refinementRetry = true; return; }
                    var chunks = new LinkedHashSet<net.minecraft.world.level.ChunkPos>();
                    for (var candidate : entityCut.candidates()) chunks.add(candidate.position());
                    for (var savedEntity : saved.entities()) {
                        if (!Double.isFinite(savedEntity.x()) || !Double.isFinite(savedEntity.z())) {
                            unmet = savedEntity.path() + ": invalid saved position retains original load failure; no input closure";
                            return;
                        }
                        chunks.add(new net.minecraft.world.level.ChunkPos(
                                ((int) Math.floor(savedEntity.x())) >> 4, ((int) Math.floor(savedEntity.z())) >> 4));
                    }
                    selectorChunks = Collections.unmodifiableSet(chunks);
                    bytes = Math.addExact(bytes, charge);
                }
                if (FullSavedTextInputs.hasOption(selector.source(), "predicate")) {
                    Set<net.minecraft.world.level.ChunkPos> origins;
                    if (ownedSelf) {
                        if (!Double.isFinite(fact.x()) || !Double.isFinite(fact.z())) {
                            unmet = fact.path() + ": invalid saved position retains original load failure; no input closure";
                            return;
                        }
                        origins = Set.of(new net.minecraft.world.level.ChunkPos(
                                ((int) Math.floor(fact.x())) >> 4, ((int) Math.floor(fact.z())) >> 4));
                    }
                    else if (entityCut != null) origins = selectorChunks;
                    else origins = Set.of();
                    var predicates = FullSelectorPredicateInputs.discover(world.getServer(), selector.source(), origins, this::reserve);
                    if (predicateRegistries != null && predicateRegistries != predicates.registries()) {
                        refinementRetry = true; return;
                    }
                    predicateRegistries = predicates.registries();
                    if (predicates.capacity()) { refinementCapacity = true; return; }
                    if (predicates.unmet() != null) {
                        unmet = fact.path() + ": " + predicates.unmet() + " for " + selector.source(); return;
                    }
                    predicateBiomeChunks.addAll(predicates.biomes());
                    predicateFullChunks.addAll(predicates.full());
                    predicateStructureChunks.addAll(predicates.structures());
                    entityPredicates.addAll(predicates.entityPredicates());
                    bytes = Math.addExact(bytes, predicates.bytes());
                    if (!predicates.storageIds().isEmpty()) {
                        var reads = predicates.storageIds().stream().map(id -> new FullSavedTextInputs.NbtRead(
                                FullSavedTextInputs.Source.STORAGE, id.toString(), null, "", false, 101)).toList();
                        var providerInputs = new FullSavedTextInputs.Requirements(List.of(), List.of(), List.of(), reads, null);
                        texts.computeIfAbsent(fact.path(), ignored -> new ArrayList<>()).add(providerInputs);
                        for (var read : reads) queue.add(new Pending(fact, read));
                    }
                }
            }
            for (var read : requirements.nbtReads()) queue.add(new Pending(fact, read));
        }
        private FullSavedTextInputs.Selector declaredSelector(String path, String source, FullSavedTextInputs.Use use) {
            return texts.getOrDefault(path, List.of()).stream().flatMap(requirements -> requirements.selectors().stream())
                    .filter(selector -> selector.source().equals(source) && selector.use() == use).findFirst().orElse(null);
        }
        public Scope enter(ChunkStageClaim claim) {
            claim.require(stage);
            if (ACTIVE.get() != null || this.claim != null || unmet != null || !valid(saved))
                throw FullFinishingServices.failure(new ChunkProtocolException("saved text contexts are stale or not admitted"));
            var service = FullFinishingServices.currentReservation(world.getServer());
            if (service == null || !service.stage().equals(stage))
                throw FullFinishingServices.failure(new ChunkProtocolException("saved text context lacks the matching named service turn"));
            service.requireActive(claim);
            this.claim = claim; ACTIVE.set(this); return new Scope(this);
        }
        @Override public void close() {
            if (closed) return;
            if (claim != null) throw new ChunkProtocolException("saved contexts released before original reader exit");
            closed = true; texts.clear(); storage.clear(); pendingBlocks.clear(); refinedBlocks.clear();
            pendingEntities.clear(); pendingLiveBlocks.clear(); refinedEntities.clear(); refinedLiveBlocks.clear(); liveReceipts.clear();
            blockTags.clear(); blockMapAbsences.clear(); blockReloadAbsences.clear();
            var waiters = new ArrayList<AutoCloseable>(blockWaiters.values()); blockWaiters.clear();
            Throwable failure = null;
            for (AutoCloseable waiter : waiters) try { waiter.close(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            for (AutoCloseable consumer : consumers) try { consumer.close(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            consumers.clear();
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure != null) throw new ChunkProtocolException("saved context acknowledgement failed", failure);
        }
    }
    public static final class Scope implements AutoCloseable {
        private Plan plan;
        private Scope(Plan plan) { this.plan = plan; }
        @Override public void close() {
            if (plan == null) return;
            if (ACTIVE.get() != plan || plan.currentText != null || plan.selectorCandidates != null)
                throw new ChunkProtocolException("saved text context scope escaped");
            ACTIVE.remove(); plan.claim = null; plan = null;
        }
    }
    /** Installed on the exact concrete reader, with its real ValueInput source alias. */
    public static void textReader(Display.TextDisplay entity, ValueInput input, Runnable original) {
        if (!FullFinishingServices.finishing()) { original.run(); return; }
        Plan plan = ACTIVE.get();
        try {
            if (plan == null || plan.currentText != null || entity.level() != plan.world || !(input instanceof FullSavedValueInput value))
                throw new ChunkProtocolException("display reader has no exact saved context occurrence");
            plan.claim.require(plan.stage);
            var reservation = FullFinishingServices.currentReservation();
            if (reservation == null || !reservation.owns(entity)) throw new ChunkProtocolException("display reader is not its admitted constructed entity");
            var tag = value.phoenium$originalSavedTag(); var source = FullSavedCollections.source(tag);
            String path = source == null ? null : source.pathOf(tag);
            if (source == null || source.snapshot() != plan.saved || !plan.texts.containsKey(path))
                throw new ChunkProtocolException("display reader lost its protected saved input path");
            plan.currentText = path;
            try { original.run(); } finally { plan.currentText = null; }
        } catch (ChunkProtocolException failure) { throw FullFinishingServices.failure(failure); }
    }
    static FullCommandStorageInputs.Capture requireStorage(CommandStorage storage, Identifier id) {
        Plan plan = ACTIVE.get();
        if (plan == null || plan.currentText == null || plan.world.getServer().getCommandStorage() != storage)
            throw FullFinishingServices.failure(new ChunkProtocolException("command storage read outside its exact saved text occurrence"));
        plan.claim.require(plan.stage);
        boolean declared = plan.texts.get(plan.currentText).stream().flatMap(requirements -> requirements.nbtReads().stream())
                .anyMatch(read -> read.source() == FullSavedTextInputs.Source.STORAGE && read.target().equals(id.toString()));
        var capture = declared ? plan.storage.get(id) : null;
        if (capture == null || !capture.valid()) throw FullFinishingServices.failure(new ChunkProtocolException("unbound or stale saved text storage input"));
        return capture;
    }
    private static Plan requireBlock(ServerLevel world, BlockPos position) {
        Plan plan = ACTIVE.get();
        if (plan == null || plan.currentText == null || plan.world != world)
            throw FullFinishingServices.failure(new ChunkProtocolException("block NBT read outside its exact saved text occurrence"));
        plan.claim.require(plan.stage);
        boolean declared = plan.texts.get(plan.currentText).stream().flatMap(requirements -> requirements.nbtReads().stream())
                .anyMatch(read -> read.source() == FullSavedTextInputs.Source.BLOCK && position.equals(read.block()));
        if (!declared) throw FullFinishingServices.failure(new ChunkProtocolException("unbound saved text block input"));
        return plan;
    }
    /** Exact BlockDataSource calls keep coordinates, readiness, serialization and exceptions in the original body. */
    public static boolean blockLoaded(ServerLevel world, BlockPos position, java.util.function.BooleanSupplier original) {
        if (!FullFinishingServices.finishing()) return original.getAsBoolean();
        requireBlock(world, position);
        return FullFinishingQueries.blockLoaded(world, position, original);
    }
    public static BlockEntity blockEntity(ServerLevel world, BlockPos position, java.util.function.Supplier<BlockEntity> original) {
        if (!FullFinishingServices.finishing()) return original.get();
        requireBlock(world, position);
        return FullFinishingQueries.blockEntity(world, position, original);
    }
    /** Marks this plan's exact retained pending-tag fact before the original IMMEDIATE getter
     * removes/promotes it. Other plans see that source removal as stale. */
    static void consumingBlockTag(net.minecraft.world.level.chunk.LevelChunk chunk, BlockPos position) {
        Plan plan = ACTIVE.get();
        if (plan == null || plan.currentText == null) throw new ChunkProtocolException("pending block tag consumed outside saved text occurrence");
        var capture = plan.blockTags.get(position);
        if (capture != null) capture.consuming();
    }
    /** Executes one admitted same-world selector body at its original component/NBT/score resolution point. */
    private static <T> T selector(String source, FullSavedTextInputs.Use use, SelectorCall<T> original) throws CommandSyntaxException {
        if (!FullFinishingServices.finishing()) return original.call();
        Plan plan = ACTIVE.get();
        if (plan == null || plan.currentText == null)
            throw FullFinishingServices.failure(new ChunkProtocolException("selector read outside its exact saved text occurrence"));
        plan.claim.require(plan.stage);
        var declared = plan.texts.get(plan.currentText).stream().flatMap(requirements -> requirements.selectors().stream())
                .filter(candidate -> candidate.source().equals(source) && candidate.use() == use).findFirst().orElse(null);
        if (declared == null)
            throw FullFinishingServices.failure(new ChunkProtocolException("unbound saved text selector input " + source));
        if (declared.self()) return original.call();
        if (!declared.worldLimited() || plan.entityCut == null)
            throw FullFinishingServices.failure(new ChunkProtocolException("selector requires unapproved cross-world authority " + source));
        var current = FullFinishingServices.forServer(plan.world.getServer()).entityFacts.cut(
                plan.world, plan.maximumSelectorCandidates);
        var permitted = new IdentityHashMap<Entity, FullEntityFacts.Fact>();
        for (var candidate : current.candidates()) {
            var route = plan.directory.resolve(new RoutingKey(plan.worldId, candidate.position().x(), candidate.position().z())).orElse(null);
            if (route == null || !route.region().equals(plan.claim.frozenRoute().region())
                    || !route.generation().equals(plan.claim.frozenRoute().generation()))
                throw FullFinishingServices.failure(new ChunkProtocolException("same-world selector candidate is outside the claimed regional enclosure"));
            permitted.put(candidate.alias(), candidate.fact());
        }
        var parentCandidates = plan.selectorCandidates;
        plan.selectorCandidates = permitted;
        try { return original.call(); }
        finally { plan.selectorCandidates = parentCandidates; }
    }
    public static <T> T selectorName(String source, SelectorCall<T> original) throws CommandSyntaxException {
        return selector(source, FullSavedTextInputs.Use.NAME, original);
    }
    public static <T> T selectorScore(String source, SelectorCall<T> original) throws CommandSyntaxException {
        return selector(source, FullSavedTextInputs.Use.SCORE, original);
    }
    public static <T> T selectorNbt(String source, SelectorCall<T> original) throws CommandSyntaxException {
        return selector(source, FullSavedTextInputs.Use.NBT, original);
    }
    /** Named selector consumers may call original virtuals only on their exact converged candidates. */
    static boolean ownsSelectorCandidate(Entity entity) {
        Plan plan = ACTIVE.get();
        if (plan == null || plan.selectorCandidates == null) return false;
        FullEntityFacts.Fact expected = plan.selectorCandidates.get(entity);
        return expected != null && entity instanceof FullEntityFacts.Access access && access.phoenium$referenceFact() == expected;
    }

    /** Checks the exact original registered entity predicate before its original body. */
    public static void requireEntityPredicate(net.minecraft.advancements.predicates.entity.EntityPredicate predicate,
                                               ServerLevel world, Entity entity) {
        if (!FullFinishingServices.finishing()) return;
        Plan plan = ACTIVE.get();
        // The vanilla null branch executes no sub-predicate and requires no entity authority.
        if (entity == null) return;
        if (plan == null || plan.currentText == null || plan.world != world
                || !plan.entityPredicates.contains(predicate))
            throw FullFinishingServices.failure(new ChunkProtocolException("unbound registered entity predicate occurrence"));
        plan.claim.require(plan.stage);
        var reservation = FullFinishingServices.currentReservation();
        if (reservation == null || !reservation.owns(entity) && !ownsSelectorCandidate(entity)
                || entity.level() != world)
            throw FullFinishingServices.failure(new ChunkProtocolException("entity predicate target is outside its exact same-world authority"));
    }
}
