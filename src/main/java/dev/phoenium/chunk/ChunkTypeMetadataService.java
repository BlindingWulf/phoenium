package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * World-instance scoped type cache. The existing custody metadata fence orders the bounded
 * validation/read/put against lifecycle and work retirement. No vanilla body, callback, future
 * completion, lease acquisition, or storage I/O executes in this critical section.
 */
public final class ChunkTypeMetadataService implements ChunkMetadataBridge {
    public static final byte ABSENT = 0;
    public static final byte REPLACEABLE = -1;
    public static final byte FULL = 1;
    public enum Marker {
        PROTO(REPLACEABLE), FULL(ChunkTypeMetadataService.FULL);
        private final byte value;
        Marker(byte value) { this.value = value; }
    }
    public enum Result { APPLIED, STALE, SHUTDOWN }
    public record Update(Result result, byte previous) { }
    private record Entry(ChunkCustodyModel.LifecycleId lifecycle, byte value) { }
    private final ChunkCustodyModel custody;
    private final ChunkWorkRegistry works;
    private final WorldId world;
    private final String serverSession;
    private final long worldInstanceSequence;
    private final Map<RoutingKey, Entry> entries = new HashMap<>();
    private boolean open = true;
    private long backingWrites;

    public ChunkTypeMetadataService(ChunkCustodyModel custody, ChunkWorkRegistry works,
                                   WorldId world, String serverSession, long worldInstanceSequence) {
        this.custody = Objects.requireNonNull(custody);
        this.works = Objects.requireNonNull(works);
        this.world = Objects.requireNonNull(world);
        this.serverSession = Objects.requireNonNull(serverSession);
        if (serverSession.isBlank() || worldInstanceSequence < 0) throw new IllegalArgumentException("invalid world instance");
        this.worldInstanceSequence = worldInstanceSequence;
    }

    public Update markPosition(ChunkCustodyModel.WorkId work, RoutingKey target, Marker marker) {
        return update(work, target, Objects.requireNonNull(marker).value);
    }

    /** Vanilla unconditionally stores -1 for an entitled operation, including FULL -> -1. */
    public Update markPositionReplaceable(ChunkCustodyModel.WorkId work, RoutingKey target) {
        return update(work, target, REPLACEABLE);
    }

    private Update update(ChunkCustodyModel.WorkId work, RoutingKey target, byte value) {
        Objects.requireNonNull(work); Objects.requireNonNull(target);
        custody.gate().lock();
        try {
            byte previous = prior(target);
            Result authorization = authorizationLocked(work, target);
            if (authorization != Result.APPLIED) return new Update(authorization, previous);
            entries.put(target, new Entry(work.lifecycle(), value));
            backingWrites++;
            return new Update(Result.APPLIED, previous);
        } finally { custody.gate().unlock(); }
    }

    /** An ordered observation, not a reservation permitting a later out-of-service write. */
    @Override public Update observe(ChunkCustodyModel.WorkId work, RoutingKey target) {
        Objects.requireNonNull(work); Objects.requireNonNull(target);
        custody.gate().lock();
        try { return new Update(authorizationLocked(work, target), prior(target)); }
        finally { custody.gate().unlock(); }
    }

    public Optional<Byte> value(RoutingKey target) {
        Objects.requireNonNull(target);
        custody.gate().lock();
        try { Entry entry = entries.get(target); return entry == null ? Optional.empty() : Optional.of(entry.value()); }
        finally { custody.gate().unlock(); }
    }

    public boolean preconditionValid(ChunkCustodyModel.WorkId work) {
        return observe(work, work.lifecycle().chunkKey()).result() == Result.APPLIED;
    }

    /** Cleanup is also a terminal fence, so a caller cannot clear then reinsert using this lineage. */
    public void retire(ChunkCustodyModel.LifecycleId lifecycle) {
        Objects.requireNonNull(lifecycle);
        custody.gate().lock();
        try {
            if (custody.lifecycleState(lifecycle).isPresent()) custody.retire(lifecycle);
            Entry entry = entries.get(lifecycle.chunkKey());
            if (entry != null && entry.lifecycle().equals(lifecycle)) entries.remove(lifecycle.chunkKey());
        } finally { custody.gate().unlock(); }
    }

    public void shutdown() {
        custody.gate().lock();
        try { open = false; entries.clear(); }
        finally { custody.gate().unlock(); }
    }
    public int entries() {
        custody.gate().lock();
        try { return entries.size(); } finally { custody.gate().unlock(); }
    }
    /** Package-private write observation for rejection regressions; exposes no backing-map access. */
    long backingWrites() {
        custody.gate().lock();
        try { return backingWrites; } finally { custody.gate().unlock(); }
    }
    private byte prior(RoutingKey key) { Entry entry = entries.get(key); return entry == null ? ABSENT : entry.value(); }
    private Result authorizationLocked(ChunkCustodyModel.WorkId work, RoutingKey target) {
        if (!open) return Result.SHUTDOWN;
        ChunkCustodyModel.LifecycleId lifecycle = work.lifecycle();
        return world.equals(lifecycle.world()) && serverSession.equals(lifecycle.serverSession())
            && worldInstanceSequence == lifecycle.worldInstanceSequence() && target.equals(lifecycle.chunkKey())
            && works.hasReservation(work) && custody.metadataEntitledLocked(work) ? Result.APPLIED : Result.STALE;
    }
}
