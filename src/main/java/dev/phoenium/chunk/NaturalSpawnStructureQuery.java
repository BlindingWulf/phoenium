package dev.phoenium.chunk;

import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RoutingKey;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.*;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;

/** One reached mobsAt query. Only immutable structure facts survive each read turn;
 * future/holder aliases below are identities for replacement validation, never facts.
 * Preparation does not poll, consume RNG, or arbitrate ordinary gameplay.
 */
public final class NaturalSpawnStructureQuery implements AutoCloseable {
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Box copy(BoundingBox box) {
            return new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        }
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }
    public record StartFact(long position, boolean valid, Box box, List<Box> pieces) {
        public StartFact { pieces = List.copyOf(pieces); }
        public boolean contains(BlockPos pos, boolean piece) {
            return valid && (piece ? pieces.stream().anyMatch(value -> value.contains(pos)) : box != null && box.contains(pos));
        }
    }
    public record ReferenceFact(Structure structure, List<Long> starts) {
        public ReferenceFact { starts = List.copyOf(starts); }
    }
    public record Snapshot(BlockPos position, List<ReferenceFact> references, List<StartFact> starts,
                           boolean fortress, WeightedList<MobSpawnSettings.SpawnerData> spawns) {
        public Snapshot {
            position = position.immutable(); references = List.copyOf(references); starts = List.copyOf(starts);
            spawns = WeightedList.of(List.copyOf(spawns.unwrap()));
        }
    }
    private record Key(ChunkPos position, ChunkStatus status) { }
    private final class Demand {
        final Key key;
        final CompletableFuture<ChunkResult<ChunkAccess>> future;
        ChunkHolder holder;
        Demand(Key key) {
            this.key = key;
            future = ((dev.phoenium.mixin.RegionalServerChunkCacheAccess) runtime.level().getChunkSource())
                .phoenium$naturalSpawnFuture(key.position.x(), key.position.z(), key.status, true);
            holder = runtime.visible(key.position);
        }
        ChunkAccess ready() {
            if (!future.isDone()) return null;
            var now = runtime.visible(key.position);
            if (holder == null) holder = now;
            if (holder == null || holder != now) { retired = true; return null; }
            ChunkAccess result = future.join().orElse(null);
            if (result == null) throw new ChunkProtocolException("natural-spawn structure dependency unloaded: " + key);
            return result instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : result;
        }
    }
    private final FullRegistrationRuntime runtime;
    private final LevelChunk source;
    private final ChunkHolder sourceHolder;
    private final BlockPos position;
    private final MobCategory category;
    private final WeightedList<MobSpawnSettings.SpawnerData> fallback;
    private final BooleanSupplier valid;
    private final Structure fortress;
    private final Map<Key, Demand> demands = new LinkedHashMap<>();
    private final CompletableFuture<Snapshot> result = new CompletableFuture<>();
    private final List<StartFact> facts = new ArrayList<>();
    private List<ReferenceFact> references;
    private List<ReferenceFact> allReferences = new ArrayList<>();
    private int entryIndex, startIndex;
    private boolean fortressPhase, retired, closed, matched;

    public NaturalSpawnStructureQuery(FullRegistrationRuntime runtime, LevelChunk source, BlockPos position,
            MobCategory category, boolean testFortress, WeightedList<MobSpawnSettings.SpawnerData> fallback,
            BooleanSupplier valid) {
        this.runtime = Objects.requireNonNull(runtime); this.source = Objects.requireNonNull(source);
        this.sourceHolder = runtime.visible(source.getPos()); this.position = position.immutable();
        this.category = category; this.fallback = WeightedList.of(List.copyOf(fallback.unwrap())); this.valid = valid;
        this.fortress = testFortress ? runtime.level().structureManager().registryAccess().lookupOrThrow(Registries.STRUCTURE)
            .getValue(BuiltinStructures.FORTRESS) : null;
        fortressPhase = fortress != null;
    }
    public CompletableFuture<Snapshot> dependency() { return result; }
    public boolean current() {
        if (closed || retired || !runtime.naturalSpawnOpen() || !valid.getAsBoolean()) return false;
        if (sourceHolder == null || runtime.visible(source.getPos()) != sourceHolder || sourceHolder.getTickingChunk() != source) return false;
        for (Demand demand : demands.values())
            if (demand.holder != null && runtime.visible(demand.key.position) != demand.holder) return false;
        return true;
    }
    /** Called at the owner-free initial observation and after an admitted native poll. */
    public void prepare() {
        runtime.requireObservationThread();
        if (OwnershipContext.current().isPresent()) throw new ChunkProtocolException("structure snapshot preparation retained owner");
        if (result.isDone() || !current()) return;
        try { advance(); }
        catch (CompletionException failure) { result.completeExceptionally(failure.getCause() == null ? failure : failure.getCause()); }
        catch (RuntimeException | Error failure) { result.completeExceptionally(failure); }
    }
    private void advance() {
        while (current()) {
            if (references == null) {
                Demand demand = demand(ChunkPos.containing(position), ChunkStatus.STRUCTURE_REFERENCES);
                ChunkAccess chunk = demand.ready(); if (chunk == null) return;
                references = read(demand, () -> {
                    var refs = ((FullStructureInputs.ChunkView) chunk).phoenium$structureReferences();
                    List<ReferenceFact> copy = new ArrayList<>();
                    if (fortressPhase) {
                        var starts = refs.get(fortress);
                        copy.add(new ReferenceFact(fortress, starts == null ? List.of() : starts.longStream().boxed().toList()));
                    } else for (var entry : refs.entrySet())
                        copy.add(new ReferenceFact(entry.getKey(), entry.getValue().longStream().boxed().toList()));
                    return List.copyOf(copy);
                });
                if (references == null) return;
                allReferences.addAll(references);
            }
            if (entryIndex == references.size()) {
                if (fortressPhase) { fortressPhase = false; references = null; entryIndex = 0; continue; }
                publish(false, fallback); return;
            }
            ReferenceFact entry = references.get(entryIndex);
            StructureSpawnOverride override = entry.structure.spawnOverrides().get(category);
            if (!fortressPhase && override == null) { entryIndex++; continue; }
            if (fortressPhase) {
                // startsForStructure materializes the entire list BEFORE getStructureAt
                // reads a bounding box. Do not warm a lazy box cache before that point.
                for (long packed : entry.starts) {
                    if (demand(ChunkPos.unpack(packed), ChunkStatus.STRUCTURE_STARTS).ready() == null) return;
                }
            }
            while (startIndex < entry.starts.size()) {
                long packed = entry.starts.get(startIndex);
                Demand demand = demand(ChunkPos.unpack(packed), ChunkStatus.STRUCTURE_STARTS);
                ChunkAccess chunk = demand.ready(); if (chunk == null) return;
                boolean piece = !fortressPhase && override.boundingBox() == StructureSpawnOverride.BoundingBoxType.PIECE;
                StartFact fact = read(demand, () -> captureStart(packed, chunk.getStartForStructure(entry.structure), piece, !matched));
                if (fact == null) return;
                facts.add(fact);
                matched |= fact.contains(position, piece);
                startIndex++;
                if (fortressPhase && matched) break;
            }
            // Native fillStartsForStructure consumes every referenced start, even
            // after its predicate has matched; only then can this override return.
            if (matched) { publish(fortressPhase, fortressPhase ? NetherFortressStructure.FORTRESS_ENEMIES : override.spawns()); return; }
            entryIndex++; startIndex = 0; matched = false;
        }
    }
    /**
     * S9-D14: answers this query inside its owner turn when that needs no structure start: the reference chunk is a
     * ticking chunk of the current owner, read under the same never-waiting structure read turn, and no referenced
     * structure with a spawn override for this category (or the fortress, when tested) has a start to read. The
     * answer is the one advance() publishes after reading those references: every reference copied, no start fact,
     * the biome fallback. Otherwise nothing is read or published and the query yields as before. The yield path's
     * create-mode future for an already ticking chunk only adds a one-tick UNKNOWN ticket, which cannot change that
     * chunk's load state; this path does not request it.
     */
    public boolean resolveOwned() {
        var active = OwnershipContext.current().orElse(null);
        if (active == null || result.isDone() || !current()) return false;
        ChunkPos at = ChunkPos.containing(position);
        ChunkHolder holder = runtime.visible(at);
        LevelChunk chunk = holder == null ? null : holder.getTickingChunk();
        if (chunk == null) return false;
        RoutingKey key = new RoutingKey(runtime.authority.world(), at.x(), at.z());
        var route = runtime.authority.directory().resolve(key).orElse(null);
        if (route == null || !route.region().equals(active.region())
                || runtime.authority.directory().pendingChange(key).filter(change -> !change.completed()).isPresent()) return false;
        try (var turn = runtime.structureMutations.tryReadNaturalSpawnOwned(this, holder)) {
            if (turn == null) return false;
            turn.require(this, holder);
            var refs = ((FullStructureInputs.ChunkView) chunk).phoenium$structureReferences();
            if (refs == null) return false;
            List<ReferenceFact> copy = new ArrayList<>();
            if (fortress != null) {
                var starts = refs.get(fortress);
                if (starts != null && !starts.isEmpty()) return false;
                copy.add(new ReferenceFact(fortress, List.of()));
            }
            for (var entry : refs.entrySet()) {
                if (entry.getKey().spawnOverrides().get(category) != null && !entry.getValue().isEmpty()) return false;
                copy.add(new ReferenceFact(entry.getKey(), entry.getValue().longStream().boxed().toList()));
            }
            allReferences.addAll(copy);
        }
        publish(false, fallback);
        return true;
    }
    private Demand demand(ChunkPos pos, ChunkStatus status) { return demands.computeIfAbsent(new Key(pos, status), Demand::new); }
    private <T> T read(Demand demand, Supplier<T> copy) {
        try (var turn = runtime.structureMutations.tryReadNaturalSpawn(this, List.of(demand.holder))) {
            if (turn == null) return null;
            RoutingKey key = new RoutingKey(runtime.authority.world(), demand.key.position.x(), demand.key.position.z());
            var route = runtime.authority.directory().resolve(key).orElse(null);
            if (route == null || runtime.authority.directory().pendingChange(key).filter(change -> !change.completed()).isPresent()) {
                retired = true; return null;
            }
            var manager = runtime.authority.leases().apply(route.region());
            var lease = manager == null ? null : manager.tryAcquire().orElse(null);
            if (lease == null) return null;
            try (lease; var owner = OwnershipContext.enter(route.region(), lease.epoch(), OperationCategory.OWNER_TASK)) {
                lease.requireCurrent(); turn.require(this, demand.holder);
                if (!current() || !route.equals(runtime.authority.directory().resolve(key).orElse(null))) { retired = true; return null; }
                return copy.get();
            }
        }
    }
    static StartFact captureStart(long position, StructureStart start, boolean piece, boolean observe) {
        if (start == null || !start.isValid()) return new StartFact(position, false, null, List.of());
        // Native PIECE predicates and skipped predicates do not initialize the start's
        // aggregate bounding-box cache. Copy only the facts that this reached read uses.
        if (!observe) return new StartFact(position, true, null, List.of());
        return new StartFact(position, true, piece ? null : Box.copy(start.getBoundingBox()),
            piece ? start.getPieces().stream().map(value -> Box.copy(value.getBoundingBox())).toList() : List.of());
    }
    private void publish(boolean fortressMatch, WeightedList<MobSpawnSettings.SpawnerData> spawns) {
        result.complete(new Snapshot(position, allReferences, facts, fortressMatch, spawns));
    }
    public Snapshot snapshot() { return result.join(); }
    public String observationClass() {
        return "references=" + (allReferences.stream().anyMatch(ref -> !ref.starts().isEmpty()) ? "PRESENT" : "EMPTY")
            + ":fortress=" + (fortress != null);
    }
    public String observation() {
        return "dimension=" + runtime.level().dimension().identifier() + " position=" + position
            + " source=" + source.getPos() + " sourceHolder=" + identity(sourceHolder)
            + " category=" + category + " fortressTest=" + (fortress != null)
            + " referenceKeys=" + allReferences.stream().flatMap(ref -> ref.starts().stream()).distinct().limit(16).toList()
            + " dependencies=" + demands.values().stream().limit(16).map(demand ->
                demand.key + ":create=true:holder=" + identity(demand.holder) + ":future=" + identity(demand.future)
                    + ":ready=" + demand.future.isDone() + ":failed=" + demand.future.isCompletedExceptionally()).toList()
            + " complete=" + result.isDone() + " current=" + current();
    }
    private static String identity(Object object) { return object == null ? "UNAVAILABLE" : Integer.toHexString(System.identityHashCode(object)); }
    @Override public void close() { closed = true; demands.clear(); references = null; allReferences = List.of(); facts.clear(); }
}
