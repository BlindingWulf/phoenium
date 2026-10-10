package dev.phoenium.chunk;

import dev.phoenium.topology.RoutingKey;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Binds a real holder's lifecycle evidence to the pure logical lifecycle ledger.
 * RegionDirectory remains the sole owner authority; this registry only proves incarnation.
 */
public final class ChunkLifecycleRegistry {
    private final ChunkCustodyModel custody;
    private final String serverSession;
    /** Small registry metadata gate only; it never protects Minecraft/gameplay mutation. */
    private final ReentrantLock metadataGate;
    private final Map<Slot, Entry> current = new HashMap<>();
    private final IdentityHashMap<Object, Entry> byHolder = new IdentityHashMap<>();

    public ChunkLifecycleRegistry(ChunkCustodyModel custody, String serverSession) {
        this.custody = Objects.requireNonNull(custody, "custody");
        this.metadataGate = custody.gate();
        this.serverSession = Objects.requireNonNull(serverSession, "serverSession");
        if (serverSession.isBlank()) throw new IllegalArgumentException("blank server session");
    }

    /** Activating the same legitimate holder is idempotent, including a pending-unload rescue. */
    public ChunkCustodyModel.LifecycleId activateHolder(long worldInstanceSequence, RoutingKey chunk, Object holder) {
        Objects.requireNonNull(chunk, "chunk"); Objects.requireNonNull(holder, "holder");
        metadataGate.lock(); try {
            Slot slot = new Slot(worldInstanceSequence, chunk);
            Entry existing = current.get(slot);
            if (existing != null && existing.holder == holder && existing.state != State.RETIRED && custodyAlive(existing.id)) {
                if (existing.state == State.PENDING_UNLOAD) custody.rescue(existing.id, holder);
                existing.state = State.ACTIVE;
                return existing.id;
            }
            if (existing != null && existing.state != State.RETIRED) {
                custody.retire(existing.id);
                existing.state = State.RETIRED;
            }
            ChunkCustodyModel.LifecycleId id = custody.activate(serverSession, worldInstanceSequence, chunk, holder);
            Entry created = new Entry(id, holder, State.ACTIVE);
            current.put(slot, created);
            byHolder.put(holder, created);
            return id;
        } finally { metadataGate.unlock(); }
    }

    public Optional<ChunkCustodyModel.LifecycleId> resolveCurrentLifecycle(long worldInstanceSequence, RoutingKey chunk) {
        metadataGate.lock(); try {
            Entry entry = current.get(new Slot(worldInstanceSequence, chunk));
            return entry == null || entry.state == State.RETIRED || !custodyAlive(entry.id) ? Optional.empty() : Optional.of(entry.id);
        } finally { metadataGate.unlock(); }
    }

    /** Holder reference equality is lifecycle evidence only, never the logical key. */
    public boolean observeSameHolder(ChunkCustodyModel.LifecycleId id, Object holder) {
        metadataGate.lock(); try {
            Entry entry = byHolder.get(holder);
            return entry != null && entry.id.equals(id) && entry.state != State.RETIRED && custodyAlive(id);
        } finally { metadataGate.unlock(); }
    }

    public boolean beginPendingUnload(ChunkCustodyModel.LifecycleId id) {
        metadataGate.lock(); try {
            Entry entry = entry(id);
            if (entry == null || entry.state != State.ACTIVE) return false;
            custody.pendingUnload(id);
            entry.state = State.PENDING_UNLOAD;
            return true;
        } finally { metadataGate.unlock(); }
    }

    /** A genuine rescue retains lifecycle/work identity when the holder is unchanged. */
    public boolean rescuePendingUnload(ChunkCustodyModel.LifecycleId id, Object holder) {
        metadataGate.lock(); try {
            Entry entry = entry(id);
            if (entry == null || entry.holder != holder || entry.state != State.PENDING_UNLOAD) return false;
            if (!custody.rescue(id, holder)) return false;
            entry.state = State.ACTIVE;
            return true;
        } finally { metadataGate.unlock(); }
    }

    public boolean retireHolder(ChunkCustodyModel.LifecycleId id, Object holder) {
        metadataGate.lock(); try {
            Entry entry = entry(id);
            if (entry == null || entry.holder != holder || entry.state == State.RETIRED) return false;
            custody.retire(id);
            entry.state = State.RETIRED;
            return true;
        } finally { metadataGate.unlock(); }
    }

    public boolean isCurrent(ChunkCustodyModel.LifecycleId id) {
        metadataGate.lock(); try {
            Entry entry = current.get(new Slot(id.worldInstanceSequence(), id.chunkKey()));
            return entry != null && entry.id.equals(id) && entry.state != State.RETIRED && custodyAlive(id);
        } finally { metadataGate.unlock(); }
    }

    /** Exact holder evidence remains checkable while a superseded graph drains its terminal edges. */
    boolean holderMatches(ChunkCustodyModel.LifecycleId id, Object holder) {
        metadataGate.lock();
        try {
            Entry entry = byHolder.get(holder);
            return entry != null && entry.holder == holder && entry.id.equals(id);
        } finally { metadataGate.unlock(); }
    }

    void releaseRetired(ChunkCustodyModel.LifecycleId id) {
        metadataGate.lock(); try {
            if (!custody.canReleaseRetiredLifecycle(id)) throw new ChunkProtocolException("holder cleanup before terminal acknowledgment");
            // S9-D15: entries of an id are created only by activateHolder, at the slot the id names and for the
            // holder its custody record names, and each map keeps one entry per key: so the entries the scans
            // below remove are the slot's and that holder's, when they carry the id. A slot that no longer holds
            // the id leaves its holder unknown here, and the scans run as before.
            Slot slot = new Slot(id.worldInstanceSequence(), id.chunkKey());
            Entry atSlot = current.get(slot);
            if (atSlot != null && atSlot.id.equals(id)) {
                current.remove(slot);
                Entry bound = byHolder.get(atSlot.holder);
                if (bound != null && bound.id.equals(id)) byHolder.remove(atSlot.holder);
                return;
            }
            byHolder.values().removeIf(entry -> entry.id.equals(id));
            current.values().removeIf(entry -> entry.id.equals(id));
        } finally { metadataGate.unlock(); }
    }
    public int retainedEntries() { metadataGate.lock(); try { return current.size() + byHolder.size(); } finally { metadataGate.unlock(); } }

    private Entry entry(ChunkCustodyModel.LifecycleId id) {
        Entry entry = current.get(new Slot(id.worldInstanceSequence(), id.chunkKey()));
        return entry != null && entry.id.equals(id) && custodyAlive(id) ? entry : null;
    }

    private boolean custodyAlive(ChunkCustodyModel.LifecycleId id) {
        return custody.lifecycleState(id).filter(state -> state != ChunkCustodyModel.LifecycleState.RETIRED).isPresent();
    }

    private enum State { ACTIVE, PENDING_UNLOAD, RETIRED }
    private record Slot(long worldInstanceSequence, RoutingKey chunk) { }
    private static final class Entry {
        private final ChunkCustodyModel.LifecycleId id;
        private final Object holder;
        private State state;
        private Entry(ChunkCustodyModel.LifecycleId id, Object holder, State state) { this.id = id; this.holder = holder; this.state = state; }
    }
}
