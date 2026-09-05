package dev.phoenium.chunk;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PotentSulfurBlockEntity.setLevel reads the shared game clock during construction.
 * PrimaryLevelData.setGameTime is its sole pinned writer. A logical read turn excludes
 * that writer for preparation; the metadata gate never spans either Minecraft body.
 */
public final class FullPreparationClock {
    private final ReentrantLock metadataGate = new ReentrantLock();
    private final Set<FullRegistrationRuntime.Graph> waiting = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<FullRegistrationRuntime.Graph> readers = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean writing;
    private long revision;
    void subscribe(FullRegistrationRuntime.Graph graph) {
        metadataGate.lock(); try { waiting.add(graph); } finally { metadataGate.unlock(); }
    }
    void forget(FullRegistrationRuntime.Graph graph) {
        metadataGate.lock(); try { waiting.remove(graph); readers.remove(graph); } finally { metadataGate.unlock(); }
    }
    boolean available() { metadataGate.lock(); try { return !writing; } finally { metadataGate.unlock(); } }
    boolean reserve(FullRegistrationRuntime.Graph graph) {
        metadataGate.lock(); try { return !writing && waiting.contains(graph) && readers.add(graph); }
        finally { metadataGate.unlock(); }
    }
    void release(FullRegistrationRuntime.Graph graph) {
        metadataGate.lock(); try { readers.remove(graph); } finally { metadataGate.unlock(); }
    }
    public void beforeWrite() {
        List<FullRegistrationRuntime.Graph> conflicts;
        metadataGate.lock(); try {
            if (writing) throw new ChunkProtocolException("overlapping game-time writers");
            conflicts = List.copyOf(readers);
            if (conflicts.isEmpty()) writing = true;
        } finally { metadataGate.unlock(); }
        if (!conflicts.isEmpty()) {
            var failure = new ChunkProtocolException("game-time writer overlaps admitted FULL preparation turn");
            conflicts.forEach(graph -> graph.protocolFailure(failure));
            throw failure;
        }
    }
    public void afterWrite() {
        List<FullRegistrationRuntime.Graph> wake;
        metadataGate.lock(); try {
            if (!writing) throw new ChunkProtocolException("unregistered game-time writer release");
            writing = false; revision = Math.incrementExact(revision); wake = List.copyOf(waiting);
        } finally { metadataGate.unlock(); }
        wake.forEach(FullRegistrationRuntime.Graph::wake);
    }
    public long revision() { metadataGate.lock(); try { return revision; } finally { metadataGate.unlock(); } }
    public int retainedEntries() { metadataGate.lock(); try { return waiting.size() + readers.size(); } finally { metadataGate.unlock(); } }
    public int activeReaders() { metadataGate.lock(); try { return readers.size(); } finally { metadataGate.unlock(); } }
}
