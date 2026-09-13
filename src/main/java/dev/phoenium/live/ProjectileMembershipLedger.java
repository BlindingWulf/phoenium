package dev.phoenium.live;

import dev.phoenium.region.RegionId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derived per-current-region/per-projectile-type membership for the Stage 7 projectile cap.
 *
 * <p>The table deliberately contains no owner epoch, lease, position, entity reference, or
 * mutation callback. Those values are resolved again through the normal object gateway when a
 * selected identity is delivered for cleanup. A region transition moves only this derived fact
 * after {@link dev.phoenium.live.EntityIndexLifecycleAdapter} has committed the native object's
 * owner transition.</p>
 *
 * <p>Admission, deterministic overflow selection, relocation and cleanup settlement are one
 * metadata transaction. The monitor never protects native entity mutation.</p>
 */
public final class ProjectileMembershipLedger {
    /** Closed operation vocabulary used by the typed ObjectOwnerGateway path. */
    public static final String DISCARD_OPERATION = "projectile-discard";

    private final Settings settings;
    private final Map<Scope, Map<ProjectileIdentity, Entry>> byScope = new HashMap<>();
    private final Map<ProjectileIdentity, Entry> byIdentity = new HashMap<>();
    private long nextAdmissionSequence;
    private boolean closed;

    public ProjectileMembershipLedger() { this(Settings.fromProperties()); }

    public ProjectileMembershipLedger(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Records one exact projectile incarnation. Repeated addressability is idempotent. If a
     * native lifecycle callback reaches this ledger before the owner-free relocation sink, the
     * existing identity is transferred to the newly resolved scope without a second admission
     * sequence.
     */
    public synchronized Admission admit(Scope scope, String objectId, long incarnation) {
        requireOpen();
        Objects.requireNonNull(scope, "scope");
        ProjectileIdentity identity = new ProjectileIdentity(objectId, incarnation);
        Entry existing = byIdentity.get(identity);
        if (existing != null) {
            if (!existing.membership.scope.projectileType.equals(scope.projectileType))
                throw new IllegalStateException("projectile identity changed type " + objectId);
            if (!existing.membership.scope.equals(scope)) {
                Membership moved = moveExisting(existing, scope);
                return new Admission(moved, false, markOldestExcess(scope));
            }
            return new Admission(existing.membership, false, List.of());
        }

        Membership membership = new Membership(scope, objectId, incarnation,
            nextAdmissionSequence = Math.incrementExact(nextAdmissionSequence));
        Entry entry = new Entry(membership);
        byIdentity.put(identity, entry);
        byScope.computeIfAbsent(scope, ignored -> new HashMap<>()).put(identity, entry);
        return new Admission(membership, true, markOldestExcess(scope));
    }

    /**
     * Rebuilds policy state from already accepted live entities at server bootstrap. Native
     * iteration order is not a policy input: identities are sorted before fresh admission
     * sequences are assigned. No cleanup may be in flight during reconstruction.
     */
    public synchronized List<Membership> reconstructDeterministically() {
        requireOpen();
        if (byIdentity.values().stream().anyMatch(entry -> entry.cleanupQueued))
            throw new IllegalStateException("cannot reconstruct projectile ledger with cleanup in flight");
        List<Membership> existing = byIdentity.values().stream().map(entry -> entry.membership)
            .sorted(MEMBERSHIP_STABLE_ORDER).toList();
        byIdentity.clear();
        byScope.clear();
        nextAdmissionSequence = 0;
        for (Membership old : existing) {
            Membership rebuilt = new Membership(old.scope, old.objectId, old.incarnation,
                nextAdmissionSequence = Math.incrementExact(nextAdmissionSequence));
            Entry entry = new Entry(rebuilt);
            ProjectileIdentity identity = rebuilt.identity();
            byIdentity.put(identity, entry);
            byScope.computeIfAbsent(rebuilt.scope, ignored -> new HashMap<>()).put(identity, entry);
        }
        List<Membership> evictions = new ArrayList<>();
        for (Scope scope : byScope.keySet().stream().sorted(SCOPE_ORDER).toList())
            evictions.addAll(markOldestExcess(scope));
        return List.copyOf(evictions);
    }

    /**
     * Transfers one exact identity after the normal object-owner transition commits. The
     * admission sequence is preserved so movement cannot make an old projectile appear new.
     * A stale or already-retired relocation fact is a no-op.
     */
    public synchronized List<Membership> relocate(String objectId, long incarnation,
                                                    RegionId oldRegion, RegionId newRegion) {
        requireOpen();
        Objects.requireNonNull(oldRegion, "oldRegion");
        Objects.requireNonNull(newRegion, "newRegion");
        if (oldRegion.equals(newRegion)) return List.of();
        Entry entry = byIdentity.get(new ProjectileIdentity(objectId, incarnation));
        if (entry == null || !entry.membership.scope.region.equals(oldRegion)) return List.of();
        Scope destination = new Scope(newRegion, entry.membership.scope.projectileType);
        moveExisting(entry, destination);
        return markOldestExcess(destination);
    }

    /** Retires only the requested exact object incarnation. */
    public synchronized boolean retire(String objectId, long incarnation) {
        requireOpen();
        return removeExact(new ProjectileIdentity(objectId, incarnation));
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

    /** Stable snapshot for diagnostics and deterministic tests. */
    public synchronized List<Membership> snapshot(Scope scope) {
        requireOpen();
        Map<ProjectileIdentity, Entry> entries = byScope.get(scope);
        if (entries == null) return List.of();
        return entries.values().stream().map(entry -> entry.membership)
            .sorted(MEMBERSHIP_ORDER).toList();
    }

    public synchronized int membershipCount() { return byIdentity.size(); }
    public synchronized int scopeCount() { return byScope.size(); }
    public Settings settings() { return settings; }

    /** Terminal server-lifetime cleanup. It never mutates a native entity. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        byIdentity.clear();
        byScope.clear();
    }

    private Membership moveExisting(Entry entry, Scope destination) {
        Membership previous = entry.membership;
        Map<ProjectileIdentity, Entry> destinationEntries = byScope.get(destination);
        Entry collision = destinationEntries == null ? null : destinationEntries.get(previous.identity());
        if (collision != null && collision != entry)
            throw new IllegalStateException("projectile destination already contains identity " + previous.identity());
        Map<ProjectileIdentity, Entry> sourceEntries = byScope.get(previous.scope);
        if (sourceEntries == null || sourceEntries.remove(previous.identity()) != entry)
            throw new IllegalStateException("projectile scope index lost exact membership " + previous.identity());
        if (sourceEntries.isEmpty()) byScope.remove(previous.scope);

        ProjectileIdentity identity = previous.identity();
        Membership moved = new Membership(destination, previous.objectId, previous.incarnation, previous.admissionSequence);
        entry.membership = moved;
        byScope.computeIfAbsent(destination, ignored -> new HashMap<>()).put(identity, entry);
        return moved;
    }

    private List<Membership> markOldestExcess(Scope scope) {
        if (!settings.enabled()) return List.of();
        Map<ProjectileIdentity, Entry> entries = byScope.get(scope);
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

    private boolean removeExact(ProjectileIdentity identity) {
        Entry removed = byIdentity.remove(identity);
        if (removed == null) return false;
        Map<ProjectileIdentity, Entry> entries = byScope.get(removed.membership.scope);
        if (entries == null || entries.remove(identity) != removed)
            throw new IllegalStateException("projectile scope index lost exact membership " + identity);
        if (entries.isEmpty()) byScope.remove(removed.membership.scope);
        return true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("projectile membership ledger lifetime has ended");
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
        .comparing((Membership value) -> value.scope.region)
        .thenComparing(value -> value.scope.projectileType)
        .thenComparing(Membership::objectId)
        .thenComparingLong(Membership::incarnation);
    private static final Comparator<Scope> SCOPE_ORDER = Comparator
        .comparing((Scope value) -> value.region)
        .thenComparing(value -> value.projectileType);

    private static final class Entry {
        private Membership membership;
        private boolean cleanupQueued;
        private Entry(Membership membership) { this.membership = membership; }
    }

    public record Settings(boolean enabled, int cap) {
        public Settings {
            if (cap < 0) throw new IllegalArgumentException("negative projectile cap");
            if (enabled && cap == 0) throw new IllegalArgumentException("enabled projectile cap must be positive");
        }

        public static Settings disabled() { return new Settings(false, 0); }

        /** Explicit opt-in requires both the boolean switch and a positive cap. */
        public static Settings fromProperties() {
            int cap = Integer.getInteger("phoenium.protection.projectileCap", 0);
            return new Settings(Boolean.getBoolean("phoenium.protection.projectileCap.enabled") && cap > 0,
                Math.max(0, cap));
        }
    }

    /** Current Phoenium owner region plus the stable registered projectile type. */
    public record Scope(RegionId region, String projectileType) {
        public Scope {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(projectileType, "projectileType");
            if (projectileType.isBlank()) throw new IllegalArgumentException("projectile type is blank");
        }
    }

    public record ProjectileIdentity(String objectId, long incarnation) {
        public ProjectileIdentity {
            Objects.requireNonNull(objectId, "objectId");
            if (objectId.isBlank()) throw new IllegalArgumentException("projectile object id is blank");
            if (incarnation < 0) throw new IllegalArgumentException("negative projectile incarnation");
        }
    }

    public record Membership(Scope scope, String objectId, long incarnation, long admissionSequence) {
        public Membership {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(objectId, "objectId");
            if (objectId.isBlank()) throw new IllegalArgumentException("projectile object id is blank");
            if (incarnation < 0) throw new IllegalArgumentException("negative projectile incarnation");
            if (admissionSequence <= 0) throw new IllegalArgumentException("projectile admission sequence must be positive");
        }

        public ProjectileIdentity identity() { return new ProjectileIdentity(objectId, incarnation); }
    }

    public record Admission(Membership membership, boolean inserted, List<Membership> cleanup) {
        public Admission {
            Objects.requireNonNull(membership, "membership");
            cleanup = List.copyOf(Objects.requireNonNull(cleanup, "cleanup"));
        }
    }
}
