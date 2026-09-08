package dev.phoenium.chunk;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/** Versioned custody for the actual pending block-entity tags. The guarded pending map and all
 * compound/list aliases publish invalidation; observation copies only dependency syntax and never
 * creates, loads, serializes or calls a block entity. */
public final class FullBlockEntityTagInputs {
    public interface Access { State phoenium$blockEntityTags(); }

    public static final class State {
        private final Map<BlockPos, Occurrence> sources = new HashMap<>();
        private Map<BlockPos, CompoundTag> guarded;
    }

    public static final class Capture {
        private final Source source;
        private final Occurrence occurrence;
        private final Reload reload;
        private final long reloadRevision;
        private final FullSavedInput.Snapshot snapshot;
        private final FullSavedTextInputs.Saved customName;
        private final long bytes;
        private boolean consumed;
        private boolean consumptionHeld;
        private int retainers;
        private Capture(Source source, Occurrence occurrence, Reload reload, long reloadRevision,
                        FullSavedInput.Snapshot snapshot) {
            this.source = source; this.occurrence = occurrence; this.reload = reload;
            this.reloadRevision = reloadRevision; this.snapshot = snapshot;
            var fact = snapshot.entities().getFirst();
            customName = fact.services().customName(); bytes = snapshot.payloadBytes();
        }
        synchronized boolean valid() {
            return source.valid(snapshot) && (consumed
                    || occurrence.valid() && (reload == null || reload.valid(reloadRevision)));
        }
        synchronized void consuming() {
            if (consumed) return;
            if (!occurrence.valid() || !source.valid(snapshot)
                    || reload != null && !reload.valid(reloadRevision))
                throw new ChunkProtocolException("pending block-entity dependency fact changed before original promotion");
            if (retainers == 0)
                throw new ChunkProtocolException("pending block-entity promotion lacks a retained dependency capture");
            source.acquire(); consumptionHeld = true;
            consumed = true;
        }
        FullSavedTextInputs.Saved customName() { return customName; }
        long bytes() { return bytes; }
        AutoCloseable retain(Runnable changed) {
            synchronized (this) { retainers = Math.incrementExact(retainers); }
            var acquired = new ArrayList<AutoCloseable>(3);
            try {
                acquired.add(source.retain(snapshot, changed));
                if (reload == null) acquired.add(occurrence.retain(changed));
                else acquired.add(reload.retain(reloadRevision, changed));
                List<AutoCloseable> uses = List.copyOf(acquired);
                var open = new java.util.concurrent.atomic.AtomicBoolean(true);
                return () -> {
                    if (!open.compareAndSet(true, false)) return;
                    Throwable failure = null;
                    try { close(uses, null); }
                    catch (Throwable problem) { failure = problem; }
                    try { releaseRetention(); }
                    catch (Throwable problem) {
                        if (failure == null) failure = problem;
                        else if (failure != problem) failure.addSuppressed(problem);
                    }
                    rethrow(failure, "pending block-entity dependency acknowledgement failed");
                };
            } catch (RuntimeException | Error failure) {
                for (AutoCloseable use : acquired) try { use.close(); }
                catch (Throwable close) { if (close != failure) failure.addSuppressed(close); }
                try { releaseRetention(); } catch (Throwable close) { if (close != failure) failure.addSuppressed(close); }
                throw failure;
            }
        }
        private void releaseRetention() {
            boolean release;
            synchronized (this) {
                if (retainers <= 0) throw new ChunkProtocolException("pending block-entity capture released twice");
                release = --retainers == 0 && consumptionHeld;
                if (release) consumptionHeld = false;
            }
            if (release) source.release();
        }
    }

    static final class Retry extends RuntimeException {
        final AutoCloseable waiter;
        Retry(AutoCloseable waiter) { this.waiter = waiter; }
    }
    static final class Capacity extends RuntimeException { }

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
                           HolderLookup.Provider registries, Runnable changed, LongPredicate reserve) {
        if (!(chunk instanceof Access access)) throw new ChunkProtocolException("prepared chunk lacks pending-tag custody");
        Occurrence occurrence;
        synchronized (access.phoenium$blockEntityTags()) {
            occurrence = access.phoenium$blockEntityTags().sources.get(position);
        }
        return occurrence == null ? null : occurrence.observe(registries, changed, reserve, () -> true, null, 0L);
    }

    public static List<CompoundTag> reloadRoots(List<CompoundTag> original) {
        var retained = new ArrayList<Occurrence>(original.size());
        try {
            for (CompoundTag tag : original) retained.add(new Occurrence(source(tag)));
            return new ReloadList(original, new Reload(retained));
        } catch (RuntimeException | Error failure) {
            close(retained, failure); throw failure;
        }
    }
    static Reload reload(List<CompoundTag> roots) {
        return roots instanceof ReloadList retained ? retained.reload : null;
    }
    /** Failure path before the vanilla reload factory can hand the parsed-list custody to its
     * callback. The original failure remains primary while every retained occurrence retires. */
    public static void failedReload(List<CompoundTag> roots, Throwable prior) {
        Reload retained = reload(roots); Throwable failure = prior;
        if (retained != null) try { retained.close(); }
        catch (Throwable close) { if (failure == null) failure = close; else if (failure != close) failure.addSuppressed(close); }
        rethrow(failure, "parsed block-entity reload factory failed");
    }

    public static final class Reload implements AutoCloseable {
        private List<Occurrence> sources;
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        private long revision;
        Reload(List<Occurrence> sources) { this.sources = sources; }
        Capture observe(ChunkPos chunk, BlockPos position, HolderLookup.Provider registries,
                        Runnable changed, LongPredicate reserve) {
            final long observed; final int size;
            synchronized (this) {
                if (sources == null) return null;
                observed = revision; size = sources.size();
            }
            for (int index = size - 1; index >= 0; index--) {
                Occurrence occurrence;
                synchronized (this) {
                    if (sources == null || revision != observed) throw retry(observed, changed);
                    occurrence = sources.get(index);
                }
                Source source = occurrence.source;
                Capture capture = occurrence.observe(registries, changed, reserve,
                        () -> retainedPosition(chunk, source.tag).equals(position), this, observed);
                if (capture != null) {
                    if (!valid(observed)) throw retry(observed, changed);
                    return capture;
                }
            }
            if (!valid(observed)) throw retry(observed, changed);
            return null;
        }
        synchronized boolean valid(long expected) { return sources != null && revision == expected; }
        AutoCloseable retain(long expected, Runnable changed) {
            var use = new ReloadUse(this, changed); listeners.add(changed);
            try { if (!valid(expected)) changed.run(); }
            catch (Throwable failure) { use.close(); throw failure; }
            return use;
        }
        Retry retry(long expected, Runnable changed) {
            AutoCloseable waiter = retain(expected, changed);
            return new Retry(waiter);
        }
        void replace(int index, Occurrence next) {
            Occurrence old;
            synchronized (this) {
                if (sources == null) { old = next; }
                else { old = sources.set(index, next); revision = Math.incrementExact(revision); }
            }
            if (old == next) { next.retire(); return; }
            notifyAndRetire(List.of(old), "parsed block-entity list replacement failed");
        }
        void add(int index, Occurrence next) {
            boolean closed;
            synchronized (this) {
                closed = sources == null;
                if (!closed) { sources.add(index, next); revision = Math.incrementExact(revision); }
            }
            if (closed) { next.retire(); return; }
            notifyAndRetire(List.of(), "parsed block-entity list insertion notification failed");
        }
        void remove(int index) {
            Occurrence old;
            synchronized (this) {
                if (sources == null) return;
                old = sources.remove(index); revision = Math.incrementExact(revision);
            }
            notifyAndRetire(List.of(old), "parsed block-entity list removal failed");
        }
        void clearOccurrences() {
            List<Occurrence> removed;
            synchronized (this) {
                if (sources == null || sources.isEmpty()) return;
                removed = List.copyOf(sources); sources.clear(); revision = Math.incrementExact(revision);
            }
            notifyAndRetire(removed, "parsed block-entity list clear failed");
        }
        private void notifyAndRetire(List<Occurrence> retiring, String message) {
            Throwable failure = notifyListeners();
            for (Occurrence occurrence : retiring) try { occurrence.retire(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            rethrow(failure, message);
        }
        private Throwable notifyListeners() {
            Throwable failure = null;
            for (Runnable listener : listeners) try { listener.run(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            return failure;
        }
        @Override public void close() {
            List<Occurrence> retiring;
            synchronized (this) {
                if (sources == null) return;
                retiring = List.copyOf(sources); sources = null; revision = Math.incrementExact(revision);
            }
            try { notifyAndRetire(retiring, "parsed block-entity tag acknowledgement failed"); }
            finally { listeners.clear(); }
        }
    }
    private static final class ReloadList extends AbstractList<CompoundTag> {
        final List<CompoundTag> delegate; final Reload reload;
        ReloadList(List<CompoundTag> delegate, Reload reload) { this.delegate = delegate; this.reload = reload; }
        @Override public int size() { return delegate.size(); }
        @Override public CompoundTag get(int index) { return delegate.get(index); }
        @Override public CompoundTag set(int index, CompoundTag value) {
            Occurrence next = new Occurrence(source(value)); CompoundTag previous;
            try { previous = delegate.set(index, value); }
            catch (RuntimeException | Error failure) { next.retire(); throw failure; }
            reload.replace(index, next); return previous;
        }
        @Override public void add(int index, CompoundTag value) {
            Occurrence next = new Occurrence(source(value));
            try { delegate.add(index, value); }
            catch (RuntimeException | Error failure) { next.retire(); throw failure; }
            reload.add(index, next);
        }
        @Override public CompoundTag remove(int index) {
            CompoundTag removed = delegate.remove(index); reload.remove(index); return removed;
        }
        @Override public void clear() {
            delegate.clear(); reload.clearOccurrences();
        }
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
            if (last) custody.discardSnapshot();
        }
        Capture observe(Occurrence occurrence, HolderLookup.Provider registries, Runnable changed,
                        LongPredicate reserve, java.util.function.BooleanSupplier selected,
                        Reload reload, long reloadRevision) {
            synchronized (this) { if (owners == 0) return null; }
            var attempt = custody.captureIfAvailable(List.of(tag), this::estimate, reserve, selected);
            if (!attempt.acquired()) throw occurrence.retry(changed, reload, reloadRevision);
            if (!attempt.selected()) return null;
            if (!attempt.capacity()) throw new Capacity();
            var snapshot = attempt.snapshot();
            if (snapshot.unmet() != null || snapshot.entities().isEmpty())
                throw new ChunkProtocolException("pending block-entity dependency fact unavailable: " + snapshot.unmet());
            // Decode is deliberately deferred to the context plan; observation only snapshots syntax.
            return new Capture(this, occurrence, reload, reloadRevision, snapshot);
        }
        private long estimate() {
            long[] budget = {0, 0};
            FullSavedTextInputs.protect(tag, null, 0, budget);
            // Snapshot payload adds generated passenger paths and fixed fact records to selected
            // component copies. This scalar deliberately overcharges each visited NBT node; it
            // allocates no detached tags and is computed inside the producer observation fence.
            return Math.addExact(Math.multiplyExact(8L, budget[1]), Math.multiplyExact(65_536L, budget[0]));
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

    private static final class Occurrence implements AutoCloseable {
        final Source source;
        final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        private boolean active = true;
        Occurrence(Source source) { this.source = source; source.acquire(); }
        Capture observe(HolderLookup.Provider registries, Runnable changed, LongPredicate reserve,
                        java.util.function.BooleanSupplier selected, Reload reload, long reloadRevision) {
            synchronized (this) { if (!active) return null; }
            return source.observe(this, registries, changed, reserve, selected, reload, reloadRevision);
        }
        synchronized boolean valid() { return active; }
        AutoCloseable retain(Runnable changed) {
            var use = new OccurrenceUse(this, changed); listeners.add(changed);
            try { if (!valid()) changed.run(); }
            catch (Throwable failure) { use.close(); throw failure; }
            return use;
        }
        Retry retry(Runnable changed, Reload reload, long reloadRevision) {
            var acquired = new ArrayList<AutoCloseable>(3);
            try {
                var sourceUse = new Use(source, changed); source.listeners.add(changed); acquired.add(sourceUse);
                if (reload == null) {
                    var occurrenceUse = new OccurrenceUse(this, changed); listeners.add(changed); acquired.add(occurrenceUse);
                } else acquired.add(reload.retain(reloadRevision, changed));
                List<AutoCloseable> uses = List.copyOf(acquired);
                AutoCloseable waiter = () -> FullBlockEntityTagInputs.close(uses, null);
                if (source.custody.available() || !valid()) changed.run();
                return new Retry(waiter);
            }
            catch (Throwable failure) {
                for (AutoCloseable use : acquired) try { use.close(); }
                catch (Throwable close) { if (close != failure) failure.addSuppressed(close); }
                throw failure;
            }
        }
        void retire() {
            synchronized (this) { if (!active) return; active = false; }
            Throwable failure = null;
            for (Runnable listener : listeners) try { listener.run(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            try { source.release(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            rethrow(failure, "pending block-entity occurrence retirement failed");
        }
        @Override public void close() { retire(); }
    }

    private static final class Use implements AutoCloseable {
        private Source source; private Runnable changed;
        Use(Source source, Runnable changed) { this.source = source; this.changed = changed; }
        @Override public synchronized void close() {
            if (source == null) return;
            source.listeners.remove(changed); source = null; changed = null;
        }
    }
    private static final class OccurrenceUse implements AutoCloseable {
        private Occurrence occurrence; private Runnable changed;
        OccurrenceUse(Occurrence occurrence, Runnable changed) { this.occurrence = occurrence; this.changed = changed; }
        @Override public synchronized void close() {
            if (occurrence == null) return;
            occurrence.listeners.remove(changed); occurrence = null; changed = null;
        }
    }
    private static final class ReloadUse implements AutoCloseable {
        private Reload reload; private Runnable changed;
        ReloadUse(Reload reload, Runnable changed) { this.reload = reload; this.changed = changed; }
        @Override public synchronized void close() {
            if (reload == null) return;
            reload.listeners.remove(changed); reload = null; changed = null;
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
            Source next = source(value); Occurrence old;
            synchronized (state) { old = state.sources.get(key); }
            CompoundTag previous = delegate.put(key, value);
            if (old != null && old.source == next) return previous;
            Occurrence occurrence = new Occurrence(next);
            synchronized (state) { state.sources.put(key.immutable(), occurrence); }
            if (old != null) old.retire();
            return previous;
        }
        @Override public void putAll(Map<? extends BlockPos, ? extends CompoundTag> values) {
            for (var entry : values.entrySet()) put(entry.getKey(), entry.getValue());
        }
        @Override public CompoundTag remove(Object key) {
            CompoundTag removed = delegate.remove(key); Occurrence old;
            synchronized (state) { old = state.sources.remove(key); }
            if (old != null) old.retire();
            return removed;
        }
        @Override public void clear() {
            delegate.clear(); List<Occurrence> old;
            synchronized (state) { old = List.copyOf(state.sources.values()); state.sources.clear(); }
            Throwable failure = null;
            for (Occurrence occurrence : old) try { occurrence.retire(); }
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
                            BlockPos key = current.getKey(); iterator.remove(); Occurrence old;
                            synchronized (state) { old = state.sources.remove(key); }
                            if (old != null) old.retire(); current = null;
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
    private static BlockPos retainedPosition(ChunkPos base, CompoundTag tag) {
        int x = tag.getIntOr("x", 0), y = tag.getIntOr("y", 0), z = tag.getIntOr("z", 0);
        if ((x >> 4) != base.x() || (z >> 4) != base.z()) {
            x = base.getBlockX(x & 15); z = base.getBlockZ(z & 15);
        }
        return new BlockPos(x, y, z);
    }
    private static void close(List<? extends AutoCloseable> sources, Throwable prior) {
        Throwable failure = prior;
        for (AutoCloseable source : sources) try { source.close(); }
        catch (Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
        rethrow(failure, "parsed block-entity tag acknowledgement failed");
    }
}
