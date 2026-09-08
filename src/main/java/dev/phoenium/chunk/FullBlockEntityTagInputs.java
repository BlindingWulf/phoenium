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
        private final Map<BlockPos, Long> revisions = new HashMap<>();
        private final Map<BlockPos, CopyOnWriteArrayList<Runnable>> listeners = new HashMap<>();
        private long sequence;
        private Map<BlockPos, CompoundTag> guarded;
        private synchronized long revision(BlockPos position) { return revisions.getOrDefault(position, 0L); }
        private AutoCloseable retain(BlockPos position, long expected, Runnable changed) {
            PositionUse use;
            synchronized (this) {
                var callbacks = listeners.computeIfAbsent(position.immutable(), ignored -> new CopyOnWriteArrayList<>());
                callbacks.add(changed); use = new PositionUse(this, position.immutable(), changed);
            }
            try { if (revision(position) != expected) changed.run(); }
            catch (Throwable failure) { use.close(); throw failure; }
            return use;
        }
        private Throwable changed(BlockPos position) {
            List<Runnable> callbacks; Throwable failure = null;
            synchronized (this) {
                try { revisions.put(position.immutable(), sequence = Math.incrementExact(sequence)); }
                catch (Throwable problem) { failure = problem; }
                callbacks = List.copyOf(listeners.getOrDefault(position, new CopyOnWriteArrayList<>()));
            }
            for (Runnable callback : callbacks) try { callback.run(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
            return failure;
        }
    }

    static final class Observation {
        final Capture capture; final Absence absence;
        Observation(Capture capture, Absence absence) { this.capture = capture; this.absence = absence; }
    }

    static final class Absence {
        private final State state; private final BlockPos position;
        private final Reload reload; private final long revision;
        Absence(State state, BlockPos position, long revision) {
            this.state = state; this.position = position.immutable(); this.reload = null; this.revision = revision;
        }
        Absence(Reload reload, long revision) {
            this.state = null; this.position = null; this.reload = reload; this.revision = revision;
        }
        boolean valid() { return state != null ? state.revision(position) == revision : reload.valid(revision); }
        AutoCloseable retain(Runnable changed) {
            return state != null ? state.retain(position, revision, changed) : reload.retain(revision, changed);
        }
    }

    public static final class Capture {
        private record ComponentMeasure(List<net.minecraft.nbt.Tag> tags, long bytes) { }
        private record ComponentCopies(List<FullSavedTextInputs.Saved> values, long bytes, boolean stale) { }
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
        /** Returns every component-valued slot of the audited serialized field. Classification
         * is deliberately conservative and does not evaluate an arbitrary NBT path. */
        List<FullSavedTextInputs.Saved> components(String path, LongPredicate reserve, Runnable changed) {
            var initial = source.custody.observeIfAvailable(() -> componentMeasure(path), changed);
            if (!initial.acquired() || initial.value() == null)
                throw retry(changed, initial.busyRegistration());
            long admitted = initial.value().bytes();
            if (!reserve.test(admitted)) throw new Capacity();
            // The reservation callback may have run a writer or lifecycle transition. Refresh
            // the exact audited slots and scalar measure before any detached copy is made.
            var copies = source.custody.observeIfAvailable(() -> {
                var refreshed = componentMeasure(path);
                if (refreshed == null) return new ComponentCopies(null, 0L, true);
                if (refreshed.bytes() > admitted) return new ComponentCopies(null, refreshed.bytes(), false);
                var result = new ArrayList<FullSavedTextInputs.Saved>(refreshed.tags().size());
                for (var tag : refreshed.tags()) result.add(FullSavedTextInputs.captureTag(tag, source.custody));
                return new ComponentCopies(List.copyOf(result), refreshed.bytes(), false);
            }, changed);
            if (!copies.acquired()) throw retry(changed, copies.busyRegistration());
            var copy = copies.value();
            if (copy == null) throw new Capacity();
            if (copy.stale()) {
                // A stale occurrence retains its normal source/reload wake waiter;
                // post-reservation growth above is a capacity refusal instead.
                throw retry(changed, copies.busyRegistration());
            }
            return copy.values();
        }

        private Retry retry(Runnable changed, AutoCloseable observation) {
            try {
                Retry base = occurrence.retry(changed, reload, reloadRevision);
                if (observation == null) return base;
                return new Retry(() -> close(List.of(base.waiter, observation), null));
            } catch (Throwable failure) {
                if (observation != null) try { observation.close(); }
                catch (Throwable close) { if (close != failure) failure.addSuppressed(close); }
                throw failure;
            }
        }
        private ComponentMeasure componentMeasure(String path) {
            if (!validUnderObservation()) return null;
            List<net.minecraft.nbt.Tag> tags = componentTags(path);
            long bytes = 0L;
            for (var tag : tags) bytes = Math.addExact(bytes, FullSavedTextInputs.measureTag(tag));
            return new ComponentMeasure(List.copyOf(tags), bytes);
        }
        private synchronized boolean validUnderObservation() {
            return source.validSnapshot(snapshot) && (consumed || occurrence.valid()
                    && (reload == null || reload.valid(reloadRevision)));
        }
        private List<net.minecraft.nbt.Tag> componentTags(String path) {
            var result = new ArrayList<net.minecraft.nbt.Tag>();
            var fields = FullNbtPathInputs.leadingFields(path, 2);
            if (!fields.isEmpty() && fields.getFirst().equals("CustomName")) {
                var tag = source.tag.get("CustomName"); if (tag != null) result.add(tag);
                return result;
            }
            if (!fields.isEmpty() && fields.getFirst().equals("LastOutput")) {
                if (!source.tag.getBooleanOr("TrackOutput", true)) return result;
                var tag = source.tag.get("LastOutput"); if (tag != null) result.add(tag);
                return result;
            }
            if (fields.size() != 2 || !(fields.getFirst().equals("front_text") || fields.getFirst().equals("back_text"))) return result;
            boolean filtered = fields.get(1).equals("filtered_messages");
            if (!filtered && !fields.get(1).equals("messages")) return result;
            var text = source.tag.getCompound(fields.getFirst()).orElse(null);
            if (text == null) return result;
            addSlots(result, text.getListOrEmpty(filtered ? "filtered_messages" : "messages"));
            if (filtered) addSlots(result, text.getListOrEmpty("messages"));
            return result;
        }
        private static void addSlots(List<net.minecraft.nbt.Tag> result, net.minecraft.nbt.ListTag values) {
            for (int index = 0; index < Math.min(4, values.size()); index++) result.add(values.get(index));
        }
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
        return observeVersioned(chunk, position, registries, changed, reserve).capture;
    }
    static Observation observeVersioned(net.minecraft.world.level.chunk.ChunkAccess chunk, BlockPos position,
                                        HolderLookup.Provider registries, Runnable changed, LongPredicate reserve) {
        if (!(chunk instanceof Access access)) throw new ChunkProtocolException("prepared chunk lacks pending-tag custody");
        State state = access.phoenium$blockEntityTags(); Occurrence occurrence; long revision;
        synchronized (state) {
            occurrence = state.sources.get(position); revision = state.revisions.getOrDefault(position, 0L);
        }
        if (occurrence == null) return new Observation(null, new Absence(state, position, revision));
        Capture capture = occurrence.observe(registries, changed, reserve, () -> true, null, 0L);
        return capture == null ? new Observation(null, new Absence(state, position, revision)) : new Observation(capture, null);
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
            return observeVersioned(chunk, position, registries, changed, reserve).capture;
        }
        Observation observeVersioned(ChunkPos chunk, BlockPos position, HolderLookup.Provider registries,
                                     Runnable changed, LongPredicate reserve) {
            final long observed; final int size;
            synchronized (this) {
                if (sources == null) return new Observation(null, new Absence(this, revision));
                observed = revision; size = sources.size();
            }
            for (int index = size - 1; index >= 0; index--) {
                Occurrence occurrence = null; boolean stale;
                synchronized (this) {
                    stale = sources == null || revision != observed;
                    if (!stale) occurrence = sources.get(index);
                }
                if (stale) throw retry(observed, changed);
                Source source = occurrence.source;
                Capture capture = occurrence.observe(registries, changed, reserve,
                        () -> retainedPosition(chunk, source.tag).equals(position), this, observed);
                if (capture != null) {
                    if (!valid(observed)) throw retry(observed, changed);
                    return new Observation(capture, null);
                }
            }
            if (!valid(observed)) throw retry(observed, changed);
            return new Observation(null, new Absence(this, observed));
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
                removed = sources; sources = new ArrayList<>(); revision = Math.incrementExact(revision);
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
                retiring = sources; sources = null; revision = Math.incrementExact(revision);
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
        boolean validSnapshot(FullSavedInput.Snapshot snapshot) {
            synchronized (this) { if (owners == 0) return false; }
            return custody.snapshot() == snapshot;
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
    private static final class PositionUse implements AutoCloseable {
        private State state; private BlockPos position; private Runnable changed;
        PositionUse(State state, BlockPos position, Runnable changed) {
            this.state = state; this.position = position; this.changed = changed;
        }
        @Override public void close() {
            State current; BlockPos key; Runnable callback;
            synchronized (this) {
                if (state == null) return;
                current = state; key = position; callback = changed;
                state = null; position = null; changed = null;
            }
            synchronized (current) {
                var callbacks = current.listeners.get(key);
                if (callbacks != null) {
                    callbacks.remove(callback);
                    if (callbacks.isEmpty()) current.listeners.remove(key);
                }
            }
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
            finishMutation(state, key, old, "pending block-entity tag replacement failed");
            return previous;
        }
        @Override public void putAll(Map<? extends BlockPos, ? extends CompoundTag> values) {
            for (var entry : values.entrySet()) put(entry.getKey(), entry.getValue());
        }
        @Override public CompoundTag remove(Object key) {
            CompoundTag removed = delegate.remove(key); Occurrence old;
            synchronized (state) { old = state.sources.remove(key); }
            if (old != null) finishMutation(state, (BlockPos) key, old, "pending block-entity tag removal failed");
            return removed;
        }
        @Override public void clear() {
            delegate.clear(); List<Map.Entry<BlockPos, Occurrence>> old;
            synchronized (state) { old = List.copyOf(state.sources.entrySet()); state.sources.clear(); }
            Throwable failure = null;
            for (var entry : old) try { finishMutation(state, entry.getKey(), entry.getValue(), "pending block-entity tag clear failed"); }
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
                            if (old != null) finishMutation(state, key, old, "pending block-entity iterator removal failed");
                            current = null;
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
    private static void finishMutation(State state, BlockPos position, Occurrence retiring, String message) {
        Throwable failure = state.changed(position);
        if (retiring != null) try { retiring.retire(); }
        catch (Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
        rethrow(failure, message);
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
