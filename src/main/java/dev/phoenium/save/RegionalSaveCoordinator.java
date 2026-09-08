package dev.phoenium.save;

import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.service.RegionalServiceDispatcher;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/** One retained record for each original dirty claim, snapshot and write acknowledgement.
 * An I/O completion only publishes an outcome; it never invokes source mutation or cleanup.
 * The record follows its current directory route while its captured owner cut remains fixed.
 * No completed-operation history or per-tick world scan is retained. */
public final class RegionalSaveCoordinator {
    public interface Source {
        RoutingKey key();
        /** Exact source/holder incarnation, rechecked immediately before every source access. */
        boolean current();
        void restoreDirty();
        /** Release protocol custody only; called after the final owned acknowledgement. */
        void release();
    }
    public record Cut(long sequence, RoutingKey key, RegionRoute owner) { }
    private final RegionDirectory directory;
    private final Map<RegionId, RegionRuntime> runtimes;
    private final RegionalServiceDispatcher dispatcher;
    private final Map<Long, Attempt> retained = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Attempt> completed = new ConcurrentLinkedQueue<>();
    private long sequence, captures, acknowledgements, failedWrites, retiredFailures;
    private Throwable unreportedFailure;

    public RegionalSaveCoordinator(RegionDirectory directory, Map<RegionId, RegionRuntime> runtimes,
                                   RegionalServiceDispatcher dispatcher) {
        this.directory = Objects.requireNonNull(directory);
        this.runtimes = Objects.requireNonNull(runtimes);
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    /** Called at the original save invocation, before its first regional mutation. */
    public Attempt begin(Source source) {
        Objects.requireNonNull(source);
        long id = sequence = Math.incrementExact(sequence);
        Attempt attempt = new Attempt(id, source);
        retained.put(id, attempt);
        return attempt;
    }

    public final class Attempt {
        private final long id;
        private Source source;
        private Cut cut;
        private Object snapshot;
        private boolean dirty, submitted, bodyFinished, acknowledged, queued;
        private volatile boolean writeFinished;
        private volatile Throwable failure;
        private Attempt(long id, Source source) { this.id = id; this.source = source; }
        public Cut cut() { return cut; }

        public boolean markSaved(Supplier<Boolean> original) {
            requireRetained();
            return owned(source.key(), RegionalServiceKind.SAVE_CAPTURE, () -> {
                requireCurrentSource();
                dirty = original.get();
                return dirty;
            });
        }

        public <T> T capture(Supplier<T> original) {
            requireRetained();
            if (!dirty || snapshot != null) throw new IllegalStateException("save capture lacks a unique original dirty claim");
            try {
                return owned(source.key(), RegionalServiceKind.SAVE_CAPTURE, () -> {
                    requireCurrentSource();
                    RegionRoute route = directory.resolve(source.key()).orElseThrow();
                    T value = Objects.requireNonNull(original.get(), "original save snapshot");
                    cut = new Cut(id, source.key(), route);
                    snapshot = value;
                    captures++;
                    return value;
                });
            } catch (RuntimeException | Error problem) { failed(problem); throw problem; }
        }

        /** Returns the exact original write future; attachment does not replace its success. */
        public <T> CompletableFuture<T> submitted(CompletableFuture<T> original) {
            requireRetained();
            if (submitted || snapshot == null) throw new IllegalStateException("write without unique captured save data");
            submitted = true;
            Objects.requireNonNull(original).whenComplete((result, problem) -> {
                synchronized (this) {
                    if (problem != null) addFailure(problem);
                    writeFinished = true;
                }
                completed.add(this);
            });
            return original;
        }

        /** Preserve failures caught/reported by the original save method as well as thrown ones. */
        public synchronized void failed(Throwable problem) {
            requireRetained();
            addFailure(problem);
        }
        private void addFailure(Throwable problem) {
            if (failure == null) failure = Objects.requireNonNull(problem);
            else if (failure != problem) failure.addSuppressed(problem);
        }

        public void finishBody() {
            requireRetained();
            if (bodyFinished) throw new IllegalStateException("save body finished twice");
            bodyFinished = true;
            if (!submitted) { writeFinished = true; completed.add(this); }
        }

        private void requireRetained() {
            if (acknowledged || retained.get(id) != this) throw new IllegalStateException("expired save attempt");
        }
        private void requireCurrentSource() {
            if (!source.current()) throw new IllegalStateException("save source/holder changed before original body");
        }
    }

    /** Only the owner-free coordinator calls this. Each source acknowledgement is a typed task. */
    public boolean pumpOne() {
        if (OwnershipContext.current().isPresent()) return false;
        Attempt attempt = completed.poll();
        if (attempt == null) return false;
        if (!attempt.bodyFinished) { completed.add(attempt); return false; }
        attempt.requireRetained();
        if (!attempt.writeFinished || attempt.queued) throw new IllegalStateException("invalid or repeated save completion");
        attempt.queued = true;
        Source source = attempt.source;
        try {
            dispatcher.executeAndDrain(source.key(), RegionalServiceKind.SAVE_ACKNOWLEDGEMENT, execution -> {
                try {
                    // Even an obsolete holder keeps its original failure. Never dirty a replacement.
                    if (attempt.failure != null) {
                        failedWrites++;
                        recordFailure(attempt.failure);
                        if (attempt.dirty && source.current()) source.restoreDirty();
                        else if (attempt.dirty) retiredFailures++;
                    }
                } finally { attempt.acknowledged = true; }
            });
        } catch (RuntimeException | Error problem) {
            if (!attempt.acknowledged) {
                attempt.queued = false;
                completed.add(attempt);
                throw problem;
            }
            recordFailure(problem);
        }
        // Publication/refcount notification may reenter or fail. Commit removal first, then
        // release independently of the original write result and outside the owner lease.
        retained.remove(attempt.id);
        attempt.snapshot = null;
        attempt.source = null;
        acknowledgements++;
        try { source.release(); } catch (RuntimeException | Error problem) { recordFailure(problem); }
        return true;
    }

    public int pending() { return retained.size(); }
    public Map<String, Long> measurements() {
        return Map.of("pending", (long) retained.size(), "completedNotAcknowledged", (long) completed.size(),
                "captures", captures, "acknowledgements", acknowledgements,
                "failedWrites", failedWrites, "retiredFailedSources", retiredFailures);
    }
    /** A normal flush cannot report success while any write or owned acknowledgement remains. */
    public void requireFlushed() {
        if (pending() != 0) throw new IllegalStateException("save flush still has " + pending() + " unacknowledged writes");
        Throwable failure = unreportedFailure;
        unreportedFailure = null;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("original chunk write failed", failure);
    }

    private void recordFailure(Throwable problem) {
        if (unreportedFailure == null) unreportedFailure = problem;
        else if (unreportedFailure != problem && problem.getCause() != unreportedFailure)
            unreportedFailure.addSuppressed(problem);
    }

    private <T> T owned(RoutingKey key, RegionalServiceKind kind, Supplier<T> original) {
        RegionRoute route = directory.resolve(key).orElseThrow(() -> new IllegalStateException("save has no current owner"));
        var active = OwnershipContext.current().orElse(null);
        if (active != null) {
            RegionRuntime runtime = runtimes.get(route.region());
            if (!active.region().equals(route.region()) || runtime == null || !route.ownerEpoch().equals(runtime.ownerEpoch()))
                throw new IllegalStateException("save attempted under a foreign active operation");
            runtime.leases().requireCurrent(active.lease());
            return original.get();
        }
        // ChunkMap.save catches original serializer exceptions outside copyOf. Return that
        // exact outcome through the typed operation before rethrowing at the original catch
        // site; an already-caught vanilla save failure is not a failed gameplay callback.
        class Result { T value; RuntimeException failure; }
        Result result = new Result();
        dispatcher.executeAndDrain(key, kind, execution -> {
            try { result.value = original.get(); }
            catch (RuntimeException failure) { result.failure = failure; }
        });
        if (result.failure != null) throw result.failure;
        return result.value;
    }
}
