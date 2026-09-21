package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/** Exclusion for structure maps and the StructureStart aliases shared with generation.
 * FEATURES can move a referenced piece's bounding box eight chunks from its center.
 * A block-write radius therefore does not describe this data's writer footprint.
 *
 * A read turn is acquired all-or-none before R and never waits. An original generation
 * worker may wait before invoking its original applyStep, without any region or metadata
 * lock. The original invocation, future and synchronous exception path stay intact.
 * Turns confer no chunk, entity, status, or gameplay authority by themselves.
 */
final class FullStructureMutationTurns {
    private final ReentrantLock metadataGate = new ReentrantLock();
    private final Map<GenerationChunkHolder, Entry> entries = new IdentityHashMap<>();
    private final Thread observationThread;
    private final Runnable changed;
    private boolean stopped;

    FullStructureMutationTurns(Thread observationThread, Runnable changed) {
        this.observationThread = observationThread; this.changed = changed;
    }
    private static final class Entry {
        final GenerationChunkHolder holder;
        int readers, writers, waitingWriters;
        CompletableFuture<Void> readExit;
        Entry(GenerationChunkHolder holder) { this.holder = holder; }
    }

    Writer beginWriter(GenerationChunkHolder center, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache) {
        int radius;
        if (step == ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES))
            radius = step.directDependencies().getRadiusOf(ChunkStatus.STRUCTURE_STARTS);
        else if (step == ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.STRUCTURE_STARTS)
                || step == ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.STRUCTURE_REFERENCES)) radius = 0;
        else return null;
        // This is the pinned finite generation cache, not a discovered saved payload.
        var holders = new ArrayList<GenerationChunkHolder>((2 * radius + 1) * (2 * radius + 1));
        for (int x = center.getPos().x() - radius; x <= center.getPos().x() + radius; x++)
            for (int z = center.getPos().z() - radius; z <= center.getPos().z() + radius; z++)
                holders.add(cache.get(x, z));
        return beginWriter(holders);
    }

    private Writer beginWriter(Collection<GenerationChunkHolder> holders) {
        final List<Entry> targets;
        metadataGate.lock(); try {
            if (stopped) throw new ChunkProtocolException("structure generation registration after shutdown");
            targets = distinct(holders).stream().map(holder -> entries.computeIfAbsent(holder, Entry::new)).toList();
            targets.forEach(entry -> entry.waitingWriters++);
        } finally { metadataGate.unlock(); }
        return new Writer(targets);
    }

    Read tryRead(ChunkCustodyModel.StageId operation, Collection<GenerationChunkHolder> holders) {
        if (operation.kind() != ChunkStageKind.FULL_FINISH || OwnershipContext.current().isPresent())
            throw new ChunkProtocolException("structure input exclusion must be reserved for FULL before regional admission");
        return tryReadExact(operation, holders);
    }

    Read tryReadNaturalSpawn(NaturalSpawnStructureQuery operation, Collection<GenerationChunkHolder> holders) {
        if (Thread.currentThread() != observationThread || OwnershipContext.current().isPresent())
            throw new ChunkProtocolException("natural-spawn structure read requires its owner-free observation thread");
        if (!operation.current()) return null;
        return tryReadExact(operation, holders);
    }

    private Read tryReadExact(Object operation, Collection<GenerationChunkHolder> holders) {
        var distinct = distinct(holders);
        metadataGate.lock(); try {
            if (stopped) return null;
            for (var holder : distinct) {
                var entry = entries.get(holder);
                if (entry != null && (entry.writers != 0 || entry.waitingWriters != 0)) return null;
            }
            var targets = distinct.stream().map(holder -> entries.computeIfAbsent(holder, Entry::new)).toList();
            for (var entry : targets) {
                if (entry.readers++ == 0) entry.readExit = new CompletableFuture<>();
            }
            return new Read(operation, targets);
        } finally { metadataGate.unlock(); }
    }

    final class Read implements AutoCloseable {
        private final Object operation;
        private List<Entry> targets;
        private Read(Object operation, List<Entry> targets) { this.operation = operation; this.targets = targets; }
        void require(Object exact, GenerationChunkHolder holder) {
            metadataGate.lock(); try {
                if (!operation.equals(exact) || targets == null || targets.stream().noneMatch(entry -> entry.holder == holder))
                    throw new ChunkProtocolException("structure input lacks its exact active exclusion turn");
            } finally { metadataGate.unlock(); }
        }
        @Override public void close() {
            var notifications = new ArrayList<CompletableFuture<Void>>();
            metadataGate.lock(); try {
                if (targets == null) return;
                for (var entry : targets) {
                    if (--entry.readers == 0) { notifications.add(entry.readExit); entry.readExit = null; }
                    removeIfUnused(entry);
                }
                targets = null;
            } finally { metadataGate.unlock(); }
            notifications.forEach(future -> future.complete(null));
            changed.run();
        }
    }
    final class Writer implements AutoCloseable {
        private List<Entry> targets;
        private boolean started;
        private Writer(List<Entry> targets) { this.targets = targets; }
        void awaitReady() {
            while (true) {
                CompletableFuture<Void> waiting = null;
                metadataGate.lock(); try {
                    if (targets == null || started) throw new ChunkProtocolException("expired or repeated structure writer admission");
                    for (Entry entry : targets) if (entry.readers != 0) { waiting = entry.readExit; break; }
                    if (waiting == null) {
                        targets.forEach(entry -> { entry.waitingWriters--; entry.writers++; });
                        started = true; return;
                    }
                } finally { metadataGate.unlock(); }
                if (Thread.currentThread() == observationThread || OwnershipContext.current().isPresent())
                    throw new ChunkProtocolException("structure generation cannot wait on an owner or its observation thread");
                // Only the existing generation worker pauses, before the original call.
                // No owner, service grant, or metadata lock is retained by this wait.
                waiting.join();
            }
        }
        @Override public void close() {
            metadataGate.lock(); try {
                if (targets == null) return;
                for (var entry : targets) {
                    if (started) entry.writers--; else entry.waitingWriters--;
                    removeIfUnused(entry);
                }
                targets = null;
            } finally { metadataGate.unlock(); }
            changed.run();
        }
    }
    void shutdown() {
        // Existing original generation calls retain their continuation and exit on
        // actual read acknowledgement. Stop cannot turn them into new registrations
        // or cancel an already reached vanilla invocation prefix.
        metadataGate.lock(); try { stopped = true; } finally { metadataGate.unlock(); }
    }
    Map<String, Long> measurements() {
        metadataGate.lock(); try {
            return Map.of("holders", (long) entries.size(), "readers", entries.values().stream().mapToLong(entry -> entry.readers).sum(),
                    "writers", entries.values().stream().mapToLong(entry -> entry.writers).sum(),
                    "waitingWriters", entries.values().stream().mapToLong(entry -> entry.waitingWriters).sum());
        } finally { metadataGate.unlock(); }
    }
    private void removeIfUnused(Entry entry) {
        if (entry.readers == 0 && entry.writers == 0 && entry.waitingWriters == 0) entries.remove(entry.holder, entry);
    }
    private static Set<GenerationChunkHolder> distinct(Collection<GenerationChunkHolder> holders) {
        Set<GenerationChunkHolder> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(holders); return result;
    }
}
