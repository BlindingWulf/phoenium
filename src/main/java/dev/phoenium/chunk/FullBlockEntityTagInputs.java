package dev.phoenium.chunk;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** Versioned custody for the actual pending block-entity tags. The guarded pending map and all
 * compound/list aliases publish invalidation; observation copies only dependency syntax and never
 * creates, loads, serializes or calls a block entity. */
public final class FullBlockEntityTagInputs {
    public interface Access { State phoenium$blockEntityTags(); }

    public static final class State {
        private final Map<BlockPos, Source> sources = new HashMap<>();
        private Map<BlockPos, CompoundTag> guarded;
    }

    public static final class Capture {
        private final Source source;
        private final FullSavedInput.Snapshot snapshot;
        private final FullSavedTextInputs.Saved customName;
        private final long bytes;
        private boolean consumed;
        private Capture(Source source, FullSavedInput.Snapshot snapshot) {
            this.source = source; this.snapshot = snapshot;
            var fact = snapshot.entities().getFirst();
            customName = fact.services().customName(); bytes = snapshot.payloadBytes();
        }
        boolean valid() { return consumed || source.valid(snapshot); }
        void consuming() {
            if (!source.valid(snapshot)) throw new ChunkProtocolException("pending block-entity dependency fact changed before original promotion");
            consumed = true;
        }
        FullSavedTextInputs.Saved customName() { return customName; }
        long bytes() { return bytes; }
        AutoCloseable retain(Runnable changed) { return source.retain(snapshot, changed); }
    }

    private static final Map<FullSavedInput, WeakReference<Source>> SOURCES = new WeakHashMap<>();
    private FullBlockEntityTagInputs() { }

    public static State create() { return new State(); }

    public static Map<BlockPos, CompoundTag> guard(State state, Map<BlockPos, CompoundTag> original) {
        Objects.requireNonNull(state); Objects.requireNonNull(original);
        if (state.guarded != null) throw new ChunkProtocolException("pending block-entity tag custody installed twice");
        var guarded = new GuardedMap(state, original); state.guarded = guarded;
        if (!original.isEmpty()) guarded.putAll(new LinkedHashMap<>(original));
        return guarded;
    }

    static Capture observe(net.minecraft.world.level.chunk.ChunkAccess chunk, BlockPos position,
                           HolderLookup.Provider registries) {
        if (!(chunk instanceof Access access)) throw new ChunkProtocolException("prepared chunk lacks pending-tag custody");
        Source source;
        synchronized (access.phoenium$blockEntityTags()) {
            source = access.phoenium$blockEntityTags().sources.get(position);
        }
        return source == null ? null : source.observe(registries);
    }

    private static final class Source {
        final FullSavedInput custody = new FullSavedInput();
        final CompoundTag tag;
        final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        Source(CompoundTag tag) {
            this.tag = tag; custody.activate(); custody.subscribe(this::changed);
            long[] budget = {0, 0}; FullSavedTextInputs.protect(tag, custody, 0, budget);
            synchronized (SOURCES) { SOURCES.put(custody, new WeakReference<>(this)); }
        }
        Capture observe(HolderLookup.Provider registries) {
            if (closed) return null;
            var snapshot = custody.snapshot();
            if (snapshot == null) snapshot = custody.capture(List.of(tag));
            if (snapshot.unmet() != null || snapshot.entities().isEmpty())
                throw new ChunkProtocolException("pending block-entity dependency fact unavailable: " + snapshot.unmet());
            // Decode is deliberately deferred to the context plan; observation only snapshots syntax.
            return new Capture(this, snapshot);
        }
        boolean valid(FullSavedInput.Snapshot snapshot) {
            return !closed && custody.available() && custody.snapshot() == snapshot;
        }
        AutoCloseable retain(FullSavedInput.Snapshot snapshot, Runnable changed) {
            var use = new Use(this, changed); listeners.add(changed);
            try { if (!valid(snapshot)) changed.run(); }
            catch (Throwable failure) { use.close(); throw failure; }
            return use;
        }
        void changed() { for (Runnable listener : listeners) listener.run(); }
        void close() {
            if (closed) return;
            closed = true; custody.discardSnapshot();
            synchronized (SOURCES) { SOURCES.remove(custody); }
            try { changed(); } finally { listeners.clear(); }
        }
    }

    private static final class Use implements AutoCloseable {
        private Source source; private Runnable changed;
        Use(Source source, Runnable changed) { this.source = source; this.changed = changed; }
        @Override public synchronized void close() {
            if (source == null) return;
            source.listeners.remove(changed); source = null; changed = null;
        }
    }

    private static Source source(CompoundTag tag) {
        FullSavedInput custody = FullSavedCollections.source(tag);
        if (custody == null) return new Source(tag);
        synchronized (SOURCES) {
            WeakReference<Source> reference = SOURCES.get(custody);
            Source source = reference == null ? null : reference.get();
            if (source == null || source.closed)
                throw new ChunkProtocolException("pending block-entity tag has foreign or expired saved-input custody");
            return source;
        }
    }

    private static final class GuardedMap extends AbstractMap<BlockPos, CompoundTag> {
        final State state; final Map<BlockPos, CompoundTag> delegate;
        GuardedMap(State state, Map<BlockPos, CompoundTag> delegate) { this.state = state; this.delegate = delegate; }
        @Override public int size() { return delegate.size(); }
        @Override public CompoundTag get(Object key) { return delegate.get(key); }
        @Override public boolean containsKey(Object key) { return delegate.containsKey(key); }
        @Override public CompoundTag put(BlockPos key, CompoundTag value) {
            Source next = source(value); CompoundTag previous = delegate.put(key, value); Source old;
            synchronized (state) { old = state.sources.put(key.immutable(), next); }
            if (old != null && old != next) old.close();
            return previous;
        }
        @Override public void putAll(Map<? extends BlockPos, ? extends CompoundTag> values) {
            for (var entry : values.entrySet()) put(entry.getKey(), entry.getValue());
        }
        @Override public CompoundTag remove(Object key) {
            CompoundTag removed = delegate.remove(key); Source old;
            synchronized (state) { old = state.sources.remove(key); }
            if (old != null) old.close();
            return removed;
        }
        @Override public void clear() {
            delegate.clear(); List<Source> old;
            synchronized (state) { old = List.copyOf(state.sources.values()); state.sources.clear(); }
            for (Source source : old) source.close();
        }
        @Override public Set<Entry<BlockPos, CompoundTag>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { return delegate.size(); }
                @Override public Iterator<Entry<BlockPos, CompoundTag>> iterator() {
                    Iterator<Entry<BlockPos, CompoundTag>> iterator = delegate.entrySet().iterator();
                    return new Iterator<>() {
                        Entry<BlockPos, CompoundTag> current;
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Entry<BlockPos, CompoundTag> next() {
                            current = iterator.next(); BlockPos key = current.getKey();
                            return new SimpleEntry<>(current) {
                                @Override public CompoundTag setValue(CompoundTag value) {
                                    CompoundTag old = GuardedMap.this.put(key, value); super.setValue(value); return old;
                                }
                            };
                        }
                        public void remove() {
                            if (current == null) throw new IllegalStateException();
                            BlockPos key = current.getKey(); iterator.remove(); Source old;
                            synchronized (state) { old = state.sources.remove(key); }
                            if (old != null) old.close(); current = null;
                        }
                    };
                }
            };
        }
    }
}
