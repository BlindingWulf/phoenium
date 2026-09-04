package dev.phoenium.chunk;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * World-scoped POI installation receipt ledger.  Its short gate protects only receipt state;
 * no Minecraft POI mutation is performed by this class or while its gate is held.
 */
public final class PoiInstallService implements ChunkPoiApplicationBridge {
    public enum Claim { WON, INSTALLING, INSTALLED, STALE, SHUTDOWN }
    public enum Settlement { INSTALLED, DUPLICATE, STALE, SHUTDOWN }
    public record InstallId(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work,
                            String sectionKey, long installSequence) {
        public InstallId {
            Objects.requireNonNull(lifecycle, "lifecycle"); Objects.requireNonNull(work, "work"); Objects.requireNonNull(sectionKey, "sectionKey");
            if (!work.lifecycle().equals(lifecycle) || sectionKey.isBlank() || installSequence < 0) throw new IllegalArgumentException("invalid POI install identity");
        }
    }
    private enum State { RESERVED, INSTALLING, INSTALLED }
    private final ChunkLifecycleRegistry lifecycles;
    private final ChunkWorkRegistry works;
    /** Receipt-only gate, never a POI storage or region/gameplay lock. */
    private final ReentrantLock gate = new ReentrantLock();
    private final Map<InstallId, State> states = new HashMap<>();
    private boolean open = true;

    public PoiInstallService(ChunkLifecycleRegistry lifecycles, ChunkWorkRegistry works) {
        this.lifecycles = Objects.requireNonNull(lifecycles, "lifecycles"); this.works = Objects.requireNonNull(works, "works");
    }
    public Claim claim(InstallId id) {
        gate.lock(); try {
            if (!open) return Claim.SHUTDOWN;
            if (!current(id)) return Claim.STALE;
            State prior = states.get(id);
            if (prior == State.INSTALLED) return Claim.INSTALLED;
            if (prior == State.INSTALLING) return Claim.INSTALLING;
            states.put(id, State.INSTALLING);
            return Claim.WON;
        } finally { gate.unlock(); }
    }
    /** Called by the eventual admitted POI adapter after its one physical installation succeeds. */
    public Settlement installed(InstallId id) {
        gate.lock(); try {
            if (!open) { states.remove(id); return Settlement.SHUTDOWN; }
            if (!current(id)) { states.remove(id); return Settlement.STALE; }
            if (states.get(id) != State.INSTALLING) return Settlement.DUPLICATE;
            states.put(id, State.INSTALLED);
            return Settlement.INSTALLED;
        } finally { gate.unlock(); }
    }
    public Optional<PoiInstallReceipt> receipt(InstallId id) {
        gate.lock(); try { return open && current(id) && states.get(id) == State.INSTALLED ? Optional.of(new PoiInstallReceipt(id)) : Optional.empty(); }
        finally { gate.unlock(); }
    }
    public void forget(InstallId id) { gate.lock(); try { states.remove(id); } finally { gate.unlock(); } }
    public void retire(ChunkCustodyModel.LifecycleId lifecycle) { gate.lock(); try { states.keySet().removeIf(id -> id.lifecycle().equals(lifecycle)); } finally { gate.unlock(); } }
    public void shutdown() { gate.lock(); try { open = false; states.clear(); } finally { gate.unlock(); } }
    public int entries() { gate.lock(); try { return states.size(); } finally { gate.unlock(); } }
    private boolean current(InstallId id) { return lifecycles.isCurrent(id.lifecycle()) && works.isCurrentWork(id.work()); }

    @Override public void prefetchOrIndex(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work, String sectionKey) {
        Objects.requireNonNull(lifecycle, "lifecycle"); Objects.requireNonNull(work, "work"); Objects.requireNonNull(sectionKey, "sectionKey");
    }
    /** Binding is deliberately deferred: this service cannot itself mutate vanilla POI storage. */
    @Override public void installOrReconcile(PendingVanillaStage<?, ?> stage) { Objects.requireNonNull(stage, "stage"); }
}
