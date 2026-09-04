package dev.phoenium.chunk;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * World-scoped ordered façade for ChunkMap's shared type-cache role.  The 64 fixed stripes are
 * bounded metadata synchronization: a stripe serializes one key, never a region or gameplay world.
 */
public final class ChunkTypeMetadataService implements ChunkMetadataBridge {
    public static final byte ABSENT = -1;
    public static final byte FULL = 1;
    public static final byte REPLACEABLE = 0;
    public enum Result { APPLIED, STALE, SHUTDOWN }
    public record Update(Result result, byte previous) { }
    private record Entry(ChunkCustodyModel.LifecycleId lifecycle, byte value) { }
    private final ChunkLifecycleRegistry lifecycles;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    /** Fixed cardinality avoids a permanent lock object for each demanded chunk. */
    private final ReentrantLock[] stripes = new ReentrantLock[64];
    private volatile boolean open = true;
    public ChunkTypeMetadataService(ChunkLifecycleRegistry lifecycles) {
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles");
        for (int index = 0; index < stripes.length; index++) stripes[index] = new ReentrantLock();
    }
    /** Equivalent to markPosition: returns the exact prior byte (or ABSENT) under the one-key order. */
    public Update markPosition(ChunkCustodyModel.LifecycleId lifecycle, String key, byte value) {
        Objects.requireNonNull(lifecycle, "lifecycle"); checkKey(key); ReentrantLock stripe = stripe(key); stripe.lock(); try {
            if (!open) return new Update(Result.SHUTDOWN, prior(key));
            if (!lifecycles.isCurrent(lifecycle)) return new Update(Result.STALE, prior(key));
            Entry previous = entries.put(key, new Entry(lifecycle, value));
            return new Update(Result.APPLIED, previous == null ? ABSENT : previous.value());
        } finally { stripe.unlock(); }
    }
    /** Equivalent to markPositionReplaceable, but cannot downgrade a current FULL marker. */
    public Update markPositionReplaceable(ChunkCustodyModel.LifecycleId lifecycle, String key) {
        Objects.requireNonNull(lifecycle, "lifecycle"); checkKey(key); ReentrantLock stripe = stripe(key); stripe.lock(); try {
            if (!open) return new Update(Result.SHUTDOWN, prior(key));
            if (!lifecycles.isCurrent(lifecycle)) return new Update(Result.STALE, prior(key));
            Entry previous = entries.get(key); byte prior = previous == null ? ABSENT : previous.value();
            if (previous == null || previous.value() != FULL) entries.put(key, new Entry(lifecycle, REPLACEABLE));
            return new Update(Result.APPLIED, prior);
        } finally { stripe.unlock(); }
    }
    public Optional<Byte> value(String key) { checkKey(key); Entry entry = entries.get(key); return entry == null ? Optional.empty() : Optional.of(entry.value()); }
    public boolean preconditionValid(ChunkCustodyModel.LifecycleId lifecycle) { return open && lifecycles.isCurrent(lifecycle); }
    public void retire(ChunkCustodyModel.LifecycleId lifecycle) { entries.entrySet().removeIf(item -> item.getValue().lifecycle().equals(lifecycle)); }
    public void shutdown() { open = false; entries.clear(); }
    public int entries() { return entries.size(); }
    private byte prior(String key) { Entry entry = entries.get(key); return entry == null ? ABSENT : entry.value(); }
    private ReentrantLock stripe(String key) { return stripes[(key.hashCode() & 0x7fffffff) % stripes.length]; }
    private static void checkKey(String key) { Objects.requireNonNull(key, "key"); if (key.isBlank()) throw new IllegalArgumentException("blank metadata key"); }
    @Override public void orderStorageMutation(ChunkCustodyModel.LifecycleId lifecycle, String metadataKey) { Objects.requireNonNull(lifecycle); checkKey(metadataKey); }
    @Override public void validateLifecycle(ChunkCustodyModel.LifecycleId lifecycle) { if (!preconditionValid(lifecycle)) throw new IllegalStateException("stale metadata lifecycle"); }
}
