package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Derived per-player/per-world ender-pearl membership.  This table deliberately contains no
 * region, owner epoch, lease, position, or mutation callback.  Those values are resolved again
 * through the normal object gateway when an overflow cleanup is delivered.
 *
 * <p>The ledger is synchronized because admission, overflow selection, and cleanup queuing form
 * one metadata transaction.  It is not a gameplay lock: no native entity is ever touched while
 * the monitor is held.</p>
 */
public final class PearlMembershipLedger {
    /** Closed operation vocabulary used by the typed ObjectOwnerGateway path. */
    public static final String DISCARD_OPERATION = "pearl-discard";

    private final Settings settings;
    private final Map<Scope, Map<PearlIdentity, Entry>> byScope = new HashMap<>();
    private final Map<PearlIdentity, Entry> byIdentity = new HashMap<>();
    private long nextAdmissionSequence;
    private boolean closed;

    public PearlMembershipLedger() { this(Settings.fromProperties()); }

    public PearlMembershipLedger(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Records one exact pearl incarnation.  Repeated addressability callbacks for the same object
     * are idempotent and cannot create a second cleanup obligation.
     */
    public synchronized Admission admit(Scope scope, String objectId, long incarnation) {
        requireOpen();
        Objects.requireNonNull(scope, "scope");
        PearlIdentity identity = new PearlIdentity(objectId, incarnation);
        Entry existing = byIdentity.get(identity);
        if (existing != null) {
            existing.reconnectable = false;
            return new Admission(existing.membership, false, List.of());
        }

        Entry reconnectable = null;
        for (Entry candidate : byIdentity.values()) {
            if (!candidate.membership.objectId.equals(objectId)) continue;
            if (!candidate.reconnectable) continue;
            if (reconnectable != null)
                throw new IllegalStateException("pearl UUID has multiple reconnectable incarnations " + objectId);
            reconnectable = candidate;
        }
        if (reconnectable != null) {
            Membership previous = reconnectable.membership;
            removeExact(previous.identity());
            Membership rebound = new Membership(scope, objectId, incarnation, previous.admissionSequence);
            Entry entry = new Entry(rebound);
            byIdentity.put(rebound.identity(), entry);
            byScope.computeIfAbsent(scope, ignored -> new HashMap<>()).put(rebound.identity(), entry);
            return new Admission(rebound, true, markOldestExcess(scope));
        }

        Membership membership = new Membership(scope, objectId, incarnation,
            nextAdmissionSequence = Math.incrementExact(nextAdmissionSequence));
        Entry entry = new Entry(membership);
        byIdentity.put(identity, entry);
        byScope.computeIfAbsent(scope, ignored -> new HashMap<>()).put(identity, entry);
        return new Admission(membership, true, markOldestExcess(scope));
    }

    /**
     * Rebuilds the derived table from already accepted live entities at server bootstrap.  Native
     * entity iteration order is not a policy input: identities are sorted before fresh admission
     * sequences are assigned.  This method is intentionally a bootstrap operation and rejects a
     * table that already contains an in-flight cleanup.
     */
    public synchronized List<Membership> reconstructDeterministically() {
        requireOpen();
        if (byIdentity.values().stream().anyMatch(entry -> entry.cleanupQueued))
            throw new IllegalStateException("cannot reconstruct pearl ledger with cleanup in flight");
        List<Membership> existing = byIdentity.values().stream().map(entry -> entry.membership)
            .sorted(MEMBERSHIP_STABLE_ORDER).toList();
        byIdentity.clear();
        byScope.clear();
        nextAdmissionSequence = 0;
        for (Membership old : existing) {
            Membership rebuilt = new Membership(old.scope, old.objectId, old.incarnation,
                nextAdmissionSequence = Math.incrementExact(nextAdmissionSequence));
            Entry entry = new Entry(rebuilt);
            PearlIdentity identity = rebuilt.identity();
            byIdentity.put(identity, entry);
            byScope.computeIfAbsent(rebuilt.scope, ignored -> new HashMap<>()).put(identity, entry);
        }
        List<Membership> evictions = new ArrayList<>();
        for (Scope scope : byScope.keySet().stream().sorted(SCOPE_ORDER).toList())
            evictions.addAll(markOldestExcess(scope));
        return List.copyOf(evictions);
    }

    /** Retires only the requested object incarnation; a later reused UUID is never removed. */
    public synchronized boolean retire(String objectId, long incarnation) {
        requireOpen();
        return removeExact(new PearlIdentity(objectId, incarnation));
    }

    /**
     * Marks an entity removed with {@code UNLOADED_WITH_PLAYER} as eligible for one later
     * same-UUID rebind.  The marker is derived lifecycle metadata only; the native object and its
     * ownership binding are still retired by the normal entity index path.
     */
    public synchronized boolean preserveForReconnect(String objectId, long incarnation) {
        requireOpen();
        Entry current = byIdentity.get(new PearlIdentity(objectId, incarnation));
        if (current == null) return false;
        current.reconnectable = true;
        return true;
    }

    /** Settles a selected cleanup obligation after discard or terminal stale rejection. */
    public synchronized boolean settleCleanup(Membership expected) {
        requireOpen();
        Objects.requireNonNull(expected, "expected");
        Entry current = byIdentity.get(expected.identity());
        if (current == null || !current.membership.equals(expected)) return false;
        return removeExact(expected.identity());
    }

    /** Exact identity/state check used immediately before the one native discard action. */
    public synchronized boolean isCurrent(Membership expected) {
        requireOpen();
        Entry current = byIdentity.get(expected.identity());
        return current != null && current.membership.equals(expected);
    }

    /** Stable snapshot for diagnostics and deterministic tests; no native alias is exposed. */
    public synchronized List<Membership> snapshot(Scope scope) {
        requireOpen();
        Map<PearlIdentity, Entry> entries = byScope.get(scope);
        if (entries == null) return List.of();
        return entries.values().stream().map(entry -> entry.membership)
            .sorted(MEMBERSHIP_ORDER).toList();
    }

    public synchronized int membershipCount() {
        return byIdentity.size();
    }

    public synchronized int scopeCount() {
        return byScope.size();
    }

    public Settings settings() { return settings; }

    /** Terminal server-lifetime cleanup.  It does not mutate any native entity. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        byIdentity.clear();
        byScope.clear();
    }

    private List<Membership> markOldestExcess(Scope scope) {
        if (!settings.enabled()) return List.of();
        Map<PearlIdentity, Entry> entries = byScope.get(scope);
        if (entries == null || entries.size() <= settings.cap()) return List.of();
        List<Entry> ordered = entries.values().stream().sorted(ENTRY_ORDER).toList();
        int excess = ordered.size() - settings.cap();
        List<Membership> selected = new ArrayList<>(excess);
        for (int i = 0; i < excess; i++) {
            Entry entry = ordered.get(i);
            if (!entry.cleanupQueued) {
                entry.cleanupQueued = true;
                selected.add(entry.membership);
            }
        }
        return List.copyOf(selected);
    }

    private boolean removeExact(PearlIdentity identity) {
        Entry removed = byIdentity.remove(identity);
        if (removed == null) return false;
        Map<PearlIdentity, Entry> entries = byScope.get(removed.membership.scope);
        if (entries == null || entries.remove(identity) != removed)
            throw new IllegalStateException("pearl scope index lost exact membership " + identity);
        if (entries.isEmpty()) byScope.remove(removed.membership.scope);
        return true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("pearl membership ledger lifetime has ended");
    }

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
        .comparingLong((Entry entry) -> entry.membership.admissionSequence)
        .thenComparing(entry -> entry.membership.objectId)
        .thenComparingLong(entry -> entry.membership.incarnation);
    private static final Comparator<Membership> MEMBERSHIP_ORDER = Comparator
        .comparingLong(Membership::admissionSequence)
        .thenComparing(Membership::objectId)
        .thenComparingLong(Membership::incarnation);
    private static final Comparator<Membership> MEMBERSHIP_STABLE_ORDER = Comparator
        .comparing((Membership value) -> value.scope.world.value())
        .thenComparing(value -> value.scope.playerId.toString())
        .thenComparing(Membership::objectId)
        .thenComparingLong(Membership::incarnation);
    private static final Comparator<Scope> SCOPE_ORDER = Comparator
        .comparing((Scope value) -> value.world.value())
        .thenComparing(value -> value.playerId.toString());

    private static final class Entry {
        private final Membership membership;
        private boolean cleanupQueued;
        private boolean reconnectable;
        private Entry(Membership membership) { this.membership = membership; }
    }

    public record Settings(boolean enabled, int cap) {
        public Settings {
            if (cap < 0) throw new IllegalArgumentException("negative pearl cap");
            if (enabled && cap == 0) throw new IllegalArgumentException("enabled pearl cap must be positive");
        }

        public static Settings disabled() { return new Settings(false, 0); }

        /** Explicit opt-in requires both the boolean switch and a positive cap. */
        public static Settings fromProperties() {
            int cap = Integer.getInteger("phoenium.protection.pearlCap", 0);
            return new Settings(Boolean.getBoolean("phoenium.protection.pearlCap.enabled") && cap > 0,
                Math.max(0, cap));
        }
    }

    public record Scope(WorldId world, UUID playerId) {
        public Scope {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    public record PearlIdentity(String objectId, long incarnation) {
        public PearlIdentity {
            Objects.requireNonNull(objectId, "objectId");
            if (objectId.isBlank()) throw new IllegalArgumentException("pearl object id is blank");
            if (incarnation < 0) throw new IllegalArgumentException("negative pearl incarnation");
        }
    }

    public record Membership(Scope scope, String objectId, long incarnation, long admissionSequence) {
        public Membership {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(objectId, "objectId");
            if (objectId.isBlank()) throw new IllegalArgumentException("pearl object id is blank");
            if (incarnation < 0) throw new IllegalArgumentException("negative pearl incarnation");
            if (admissionSequence <= 0) throw new IllegalArgumentException("pearl admission sequence must be positive");
        }

        public PearlIdentity identity() { return new PearlIdentity(objectId, incarnation); }
    }

    public record Admission(Membership membership, boolean inserted, List<Membership> cleanup) {
        public Admission {
            Objects.requireNonNull(membership, "membership");
            cleanup = List.copyOf(Objects.requireNonNull(cleanup, "cleanup"));
        }
    }
}
