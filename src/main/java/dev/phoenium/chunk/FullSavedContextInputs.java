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
    @FunctionalInterface public interface SelectorCall<T> { T call() throws CommandSyntaxException; }

    public static final class Plan implements AutoCloseable {
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
        private final Map<BlockPos, FullBlockEntityTagInputs.Capture> blockTags = new HashMap<>();
        private final Map<BlockPos, AutoCloseable> blockWaiters = new HashMap<>();
        private final List<AutoCloseable> consumers = new ArrayList<>();
        private final Runnable changed;
        private final java.util.function.LongPredicate capacity;
        private FullEntityFacts.Cut entityCut;
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
        public boolean valid(FullSavedInput.Snapshot current) {
            return !closed && current == saved && world.registryAccess() == registry
                    && storage.values().stream().allMatch(FullCommandStorageInputs.Capture::valid)
                    && blockTags.values().stream().allMatch(FullBlockEntityTagInputs.Capture::valid)
                    && (entityCut == null || FullFinishingServices.forServer(world.getServer()).entityFacts.valid(entityCut));
        }
        List<FullSavedTextInputs.Requirements> requirements(String path) { return List.copyOf(texts.getOrDefault(path, List.of())); }
        Set<BlockPos> blocks() {
            var result = new LinkedHashSet<BlockPos>();
            for (var requirements : texts.values()) result.addAll(requirements.stream().flatMap(r -> r.blocks().stream()).toList());
            return Collections.unmodifiableSet(result);
        }
        Set<net.minecraft.world.level.ChunkPos> selectorChunks() {
            if (entityCut == null) return Set.of();
            var result = new LinkedHashSet<net.minecraft.world.level.ChunkPos>();
            for (var candidate : entityCut.candidates()) result.add(candidate.position());
            return Collections.unmodifiableSet(result);
        }
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
                        if (selector != null && !read.interpreting() && (selector.self() || selector.worldLimited())) continue;
                        if (selector != null && selector.self() && read.interpreting() && read.recursionDepth() > 100)
                            continue;
                        if (selector != null && selector.self() && read.interpreting() && read.path().equals("CustomName")) {
                            FullSavedTextInputs.decode(fact.services().customName(), world.registryAccess()).ifPresent(component ->
                                    add(fact, FullSavedTextInputs.discoverInterpreted(fact, component, read.recursionDepth()), queue));
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
                        capture = FullBlockEntityTagInputs.observe(target.actual(), read.block(), world.registryAccess(), changed, this::reserve);
                        if (capture == null && target.actual() instanceof FullSavedInput.PostLoadAccess access
                                && access.phoenium$postLoad() instanceof FullSavedInput.ReloadProcessor reload)
                            capture = reload.blockEntities().observe(target.actual().getPos(), read.block(),
                                    world.registryAccess(), changed, this::reserve);
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
                refinedBlocks.add(pending); changedPlan = true;
                if (capture == null) continue; // Instantiated named-field producer is a separate audited source.
                if (!read.path().equals("CustomName")) {
                    unmet = pending.fact.path() + ": pending block-entity interpreted path producer pending for " + read.path();
                    break;
                }
                var queue = new ArrayDeque<Pending>();
                FullSavedTextInputs.decode(capture.customName(), world.registryAccess()).ifPresent(component ->
                        add(pending.fact, FullSavedTextInputs.discoverInterpreted(pending.fact, component, read.recursionDepth()), queue));
                process(queue);
            }
            return changedPlan;
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
                if (FullSavedTextInputs.hasOption(selector.source(), "predicate")) {
                    unmet = fact.path() + ": registered selector predicate context producer pending for " + selector.source(); return;
                }
                boolean ownedSelf = selector.self();
                boolean identityScore = selector.use() == FullSavedTextInputs.Use.SCORE && !selector.source().startsWith("@");
                if (!ownedSelf && !identityScore && !selector.worldLimited()) {
                    unmet = fact.path() + ": protected display selector context producer pending for " + selector.source(); return;
                }
                if (!ownedSelf && !identityScore && entityCut == null) {
                    var facts = FullFinishingServices.forServer(world.getServer()).entityFacts;
                    var estimate = facts.estimate(world);
                    long charge = Math.multiplyExact(64L, estimate.candidates());
                    if (!reserve(charge)) { refinementCapacity = true; return; }
                    entityCut = facts.observe(world, estimate);
                    if (entityCut == null) { refinementRetry = true; return; }
                    bytes = Math.addExact(bytes, charge);
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
            blockTags.clear(); var waiters = List.copyOf(blockWaiters.values()); blockWaiters.clear();
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
        var current = FullFinishingServices.forServer(plan.world.getServer()).entityFacts.cut(plan.world);
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
}
