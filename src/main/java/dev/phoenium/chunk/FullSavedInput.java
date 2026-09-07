package dev.phoenium.chunk;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Immutable discovery facts, captured under the retained input's generation/load custody.
 * No entity constructor, reader, shape query, random source or live entity lookup is used here.
 */
public final class FullSavedInput {
    public interface Access { FullSavedInput phoenium$savedInput(); }
    public record EntityFact(String path, String type, double x, double y, double z, int age,
                             boolean contextualDimensions, FullSavedServicesFacts services) {
        public EntityFact(String path, String type, double x, double y, double z, int age, boolean contextualDimensions) {
            this(path, type, x, y, z, age, contextualDimensions, FullSavedServicesFacts.EMPTY);
        }
    }
    public record Snapshot(long revision, List<EntityFact> entities, long payloadBytes, String unmet) { }
    /** Normal scheduling allowance, not a limit on valid saved data. Oversized operations
     * use the input scheduler's single separately charged expansion slot. */
    public static final int MAX_ENTITIES = 4096, MAX_DEPTH = 512, MAX_FACT_BYTES = 64 * 1024;
    private long revision;
    private final java.util.concurrent.atomic.AtomicBoolean observing = new java.util.concurrent.atomic.AtomicBoolean();
    private Thread writer;
    private volatile boolean tracking;
    private volatile Snapshot snapshot;
    private final Runnable mutationBoundary;
    private final Map<CompoundTag, String> paths = new IdentityHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> changed = new java.util.concurrent.atomic.AtomicReference<>();

    public FullSavedInput() { this(() -> { }); }
    FullSavedInput(Runnable mutationBoundary) { this.mutationBoundary = Objects.requireNonNull(mutationBoundary); }

    /** Original input custody protects aliases; the nonblocking observation turn excludes addEntity. */
    public Snapshot capture(List<CompoundTag> roots) {
        tracking = true;
        if (!observing.compareAndSet(false, true)) throw new ChunkProtocolException("saved input observation overlaps its writer");
        try { return captureExclusive(roots); } finally { endWrite(); }
    }
    private Snapshot captureExclusive(List<CompoundTag> roots) {
        paths.clear();
        var facts = new ArrayList<EntityFact>();
        String unmet = null;
        try {
            for (int i = 0; i < roots.size(); i++) visit(roots.get(i), "root[" + i + "]", 0, facts);
        } catch (Limit | ChunkProtocolException limit) { unmet = limit.getMessage(); facts.clear(); }
        long bytes = 0;
        for (var fact : facts) bytes = Math.addExact(bytes, factBytes(fact));
        Snapshot next = new Snapshot(revision, List.copyOf(facts), bytes, unmet);
        snapshot = next;
        return next;
    }
    private void visit(CompoundTag tag, String path, int depth, List<EntityFact> facts) {
        if (depth > MAX_DEPTH) throw new Limit("saved root/passenger structure exceeds the serialized NBT depth limit");
        FullSavedCollections.watch(tag, this);
        paths.put(tag, path);
        tag.getList("Pos").ifPresent(pos -> FullSavedCollections.watch(pos, this));
        String type = tag.getStringOr("id", "");
        FullSavedServicesFacts serviceFacts = FullSavedServicesFacts.capture(tag, type, this);
        Vec3 pos = tag.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
        facts.add(new EntityFact(path, type, clamp(pos.x, 3.0000512E7), clamp(pos.y, 2.0E7),
                clamp(pos.z, 3.0000512E7), tag.getIntOr("Age", 0),
                tag.contains("attributes") || tag.contains("sleeping_pos") || tag.contains("active_effects"), serviceFacts));
        var passengers = tag.getListOrEmpty("Passengers");
        tag.getList("Passengers").ifPresent(list -> FullSavedCollections.watch(list, this));
        for (int i = 0; i < passengers.size(); i++) {
            var child = passengers.getCompound(i);
            if (child.isPresent()) visit(child.get(), path + "/passenger[" + i + "]", depth + 1, facts);
        }
    }
    private static long factBytes(EntityFact fact) {
        long names = Math.addExact(fact.path.length(), fact.type.length());
        return Math.addExact(Math.addExact(80L, Math.multiplyExact(2L, names)), fact.services.payloadBytes());
    }
    private static double clamp(double value, double limit) { return Math.max(-limit, Math.min(limit, value)); }
    private static final class Limit extends IllegalArgumentException { Limit(String reason) { super(reason); } }

    public Snapshot snapshot() { return snapshot; }
    public String pathOf(CompoundTag exactTag) { return snapshot == null ? null : paths.get(exactTag); }
    public void activate() { tracking = true; }
    public boolean tracking() { return tracking; }
    public boolean available() { return !observing.get(); }
    public void discardSnapshot() { snapshot = null; paths.clear(); }
    public void subscribe(Runnable listener) {
        if (!changed.compareAndSet(null, listener) && changed.get() != listener)
            throw new ChunkProtocolException("saved input already has a retained producer consumer");
    }
    public void unsubscribe(Runnable listener) { changed.compareAndSet(listener, null); }
    public void beforeWrite() {
        if (!tracking) return;
        mutationBoundary.run();
        if (!observing.compareAndSet(false, true)) throw new ChunkProtocolException("saved input producer overlaps its exclusive observation turn");
        writer = Thread.currentThread();
    }
    public void endWrite() { writer = null; observing.set(false); }
    <T> T mutate(java.util.function.Supplier<T> action) {
        if (!tracking) return action.get();
        if (observing.get() && writer == Thread.currentThread()) return action.get();
        beforeWrite();
        try { return action.get(); }
        finally {
            revision++; snapshot = null;
            Runnable notify = changed.get(); endWrite();
            if (notify != null) notify.run();
        }
    }
    /** Original addEntity publishes updated facts before releasing its exclusive input turn. */
    public Runnable written(List<CompoundTag> roots) {
        if (!tracking) return null;
        if (!observing.get()) throw new ChunkProtocolException("saved input writer lacks its exclusive turn");
        revision++;
        snapshot = null;
        return changed.get();
    }

    /** Called at the real reload post-load-processor producer, while its retained parsed data is exclusive. */
    public static List<CompoundTag> reloadRoots(List<CompoundTag> entities) {
        return FullSavedCollections.retainedReloadRoots(entities, new FullSavedInput());
    }
    public static LevelChunk.PostLoadProcessor reload(LevelChunk.PostLoadProcessor original, List<CompoundTag> entities) {
        FullSavedInput source = FullSavedCollections.source(entities);
        if (source == null) throw new ChunkProtocolException("reload processor did not retain its actual guarded saved-input list");
        return new ReloadProcessor(original, source, entities);
    }
    public record ReloadProcessor(LevelChunk.PostLoadProcessor original, FullSavedInput input, List<CompoundTag> entities) implements LevelChunk.PostLoadProcessor {
        @Override public void run(LevelChunk chunk) { if (original != null) original.run(chunk); }
    }
    public interface PostLoadAccess { LevelChunk.PostLoadProcessor phoenium$postLoad(); }

    /** The pinned Entity/CollisionGetter/BlockCollisions horizontal envelope, including both attempts. */
    static Set<ChunkPos> collisionPositions(EntityFact fact, int limit) {
        double previous = fact.contextualDimensions() ? 0.0 : EntityTypes.TADPOLE.getDimensions().width();
        double next = EntityTypes.FROG.getDimensions().width();
        double half = (Math.max(0.0F, (float) next - (float) previous) + 1.0E-6) / 2 + next;
        int x0 = ((int) Math.floor(fact.x - half - 1.0E-7) - 1) >> 4;
        int x1 = ((int) Math.floor(fact.x + half + 1.0E-7) + 1) >> 4;
        int z0 = ((int) Math.floor(fact.z - half - 1.0E-7) - 1) >> 4;
        int z1 = ((int) Math.floor(fact.z + half + 1.0E-7) + 1) >> 4;
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > limit) throw new Limit("collision window exceeds admission bound");
        Set<ChunkPos> result = new LinkedHashSet<>();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) result.add(new ChunkPos(x, z));
        return result;
    }
    static boolean converts(EntityFact fact) { return fact.age >= Tadpole.ticksToBeFrog; }
}
