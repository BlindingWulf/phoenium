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
        synchronized boolean valid() { return consumed || source.valid(snapshot); }
        synchronized void consuming() {
            if (consumed) return;
            if (!source.valid(snapshot)) throw new ChunkProtocolException("pending block-entity dependency fact changed before original promotion");
            consumed = true;
        }
        FullSavedTextInputs.Saved customName() { return customName; }
        long bytes() { return bytes; }
        AutoCloseable retain(Runnable changed) { return source.retain(snapshot, changed); }
    }

    static final class Retry extends RuntimeException {
        final AutoCloseable waiter;
        Retry(AutoCloseable waiter) { this.waiter = waiter; }
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
                           HolderLookup.Provider registries, Runnable changed) {
        if (!(chunk instanceof Access access)) throw new ChunkProtocolException("prepared chunk lacks pending-tag custody");
        Source source;
        synchronized (access.phoenium$blockEntityTags()) {
            source = access.phoenium$blockEntityTags().sources.get(position);
        }
        return source == null ? null : source.observe(registries, changed);
    }

    private static final class Source {
        final FullSavedInput custody = new FullSavedInput();
        final CompoundTag tag;
        final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        int owners;
        Source(CompoundTag tag) {
            this.tag = tag; custody.activate(); custody.subscribe(this::changed);
            long[] budget = {0, 0}; FullSavedTextInputs.protect(tag, custody, 0, budget);
            synchronized (SOURCES) { SOURCES.put(custody, new WeakReference<>(this)); }
        }
        synchronized void acquire() { owners = Math.incrementExact(owners); }
        void release() {
            boolean last;
            synchronized (this) {
                if (owners <= 0) throw new ChunkProtocolException("pending block-entity tag source released without a map occurrence");
                last = --owners == 0;
            }
            if (last) { custody.discardSnapshot(); changed(); }
        }
        synchronized Capture observe(HolderLookup.Provider registries, Runnable changed) {
            if (owners == 0) return null;
            var attempt = custody.captureIfAvailable(List.of(tag));
            if (!attempt.acquired()) throw retry(changed);
            var snapshot = attempt.snapshot();
            if (snapshot.unmet() != null || snapshot.entities().isEmpty())
                throw new ChunkProtocolException("pending block-entity dependency fact unavailable: " + snapshot.unmet());
            // Decode is deliberately deferred to the context plan; observation only snapshots syntax.
            return new Capture(this, snapshot);
        }
        boolean valid(FullSavedInput.Snapshot snapshot) {
            synchronized (this) { if (owners == 0) return false; }
            return custody.available() && custody.snapshot() == snapshot;
        }
        AutoCloseable retain(FullSavedInput.Snapshot snapshot, Runnable changed) {
            var use = new Use(this, changed); listeners.add(changed);
            try { if (!valid(snapshot)) changed.run(); }
            catch (Throwable failure) { use.close(); throw failure; }
            return use;
        }
        Retry retry(Runnable changed) {
            var waiter = new Use(this, changed); listeners.add(changed);
            if (custody.available()) changed.run();
            return new Retry(waiter);
        }
        void changed() {
            Throwable failure = null;
            for (Runnable listener : listeners) try { listener.run(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            rethrow(failure, "pending block-entity dependency notification failed");
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
            if (source == null)
                throw new ChunkProtocolException("pending block-entity tag has foreign saved-input custody");
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
            Source next = source(value); Source old;
            synchronized (state) { old = state.sources.get(key); }
            CompoundTag previous = delegate.put(key, value);
            if (old == next) return previous;
            next.acquire();
            synchronized (state) { state.sources.put(key.immutable(), next); }
            if (old != null) old.release();
            return previous;
        }
        @Override public void putAll(Map<? extends BlockPos, ? extends CompoundTag> values) {
            for (var entry : values.entrySet()) put(entry.getKey(), entry.getValue());
        }
        @Override public CompoundTag remove(Object key) {
            CompoundTag removed = delegate.remove(key); Source old;
            synchronized (state) { old = state.sources.remove(key); }
            if (old != null) old.release();
            return removed;
        }
        @Override public void clear() {
            delegate.clear(); List<Source> old;
            synchronized (state) { old = List.copyOf(state.sources.values()); state.sources.clear(); }
            Throwable failure = null;
            for (Source source : old) try { source.release(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            rethrow(failure, "pending block-entity tag clear failed");
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
                            if (old != null) old.release(); current = null;
                        }
                    };
                }
            };
        }
    }

    private static void rethrow(Throwable failure, String message) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new ChunkProtocolException(message, failure);
    }
}
