package dev.phoenium.live;

import dev.phoenium.region.RegionId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Derived, bounded per-region accounting. Never stores an entity alias or mutation authority. */
public final class MobDecisionScheduler {
    public static final int COHORTS = 4;
    public static final int MAX_ADDED_TICKS = 6;
    public enum Category { HOSTILE, PASSIVE }
    public record Scope(RegionId region, Category category) {
        public Scope { Objects.requireNonNull(region); Objects.requireNonNull(category); }
    }
    public record Settings(boolean enabled, int threshold) {
        public Settings { if (threshold < 1) throw new IllegalArgumentException("mob decision threshold must be positive"); }
        public static Settings fromProperties() {
            return new Settings(Boolean.getBoolean("phoenium.protection.mobDecisions.enabled"),
                Integer.getInteger("phoenium.protection.mobDecisions.threshold", 64));
        }
    }
    private final Settings settings;
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final Map<Scope, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongAdder allowed = new LongAdder(), deferred = new LongAdder(), deadlines = new LongAdder();
    private volatile boolean closed;

    public MobDecisionScheduler(Settings settings) { this.settings = Objects.requireNonNull(settings); }
    public Settings settings() { return settings; }

    /** Exact native incarnation differs from the object registry's UUID-reuse incarnation. */
    public void observe(String id, long nativeIncarnation, long registryIncarnation, Scope scope) {
        if (!settings.enabled || closed) return;
        Objects.requireNonNull(id); Objects.requireNonNull(scope);
        members.compute(id, (key, old) -> {
            if (old != null && old.nativeIncarnation > nativeIncarnation) return old;
            if (old != null && old.nativeIncarnation == nativeIncarnation && old.registryIncarnation == registryIncarnation) {
                move(old, scope);
                return old;
            }
            if (old != null) detach(old);
            Member fresh = new Member(id, nativeIncarnation, registryIncarnation, scope);
            attach(fresh);
            return fresh;
        });
    }

    /** Called only after the existing registry commits relocation, never from raw position observation. */
    public void relocate(String id, long registryIncarnation, RegionId destination) {
        if (!settings.enabled || closed) return;
        members.computeIfPresent(id, (key, member) -> {
            if (member.registryIncarnation == registryIncarnation)
                move(member, new Scope(destination, member.scope.category));
            return member;
        });
    }

    public void retire(String id, long nativeIncarnation) {
        if (!settings.enabled || closed) return;
        members.computeIfPresent(id, (key, member) -> {
            if (member.nativeIncarnation != nativeIncarnation) return member;
            detach(member);
            return null;
        });
    }

    /**
     * The caller has already revalidated native identity and current owner. At most one gate
     * decision per entity tick, shared by both selectors. No traversal of population is performed.
     */
    public boolean allow(String id, long incarnation, Scope scope, int entityTick, long worldTick, boolean exempt) {
        if (!settings.enabled || closed) return true;
        boolean[] result = {true};
        members.computeIfPresent(id, (key, member) -> {
            if (member.nativeIncarnation != incarnation) return member;
            move(member, scope);
            Bucket bucket = buckets.get(scope);
            if (bucket == null) throw new IllegalStateException("mob decision scope absent");
            if (exempt) { member.pending = false; member.allow = true; return member; }
            if (member.hasTick && member.tick == entityTick) { result[0] = member.allow; return member; }
            boolean overdue = member.pending && elapsed(entityTick, member.firstDeferred) >= MAX_ADDED_TICKS;
            boolean run = exempt || bucket.size <= settings.threshold || overdue
                || Math.floorMod(Math.floorDiv(worldTick, 2), COHORTS) == member.phase;
            if (run) {
                if (overdue) { deadlines.increment(); member.deadlines++; }
                member.pending = false;
                allowed.increment(); member.allowed++;
            } else {
                if (!member.pending) { member.pending = true; member.firstDeferred = entityTick; }
                deferred.increment(); member.deferred++;
            }
            member.hasTick = true; member.tick = entityTick; member.allow = run;
            result[0] = run;
            return member;
        });
        return result[0];
    }

    // Subtraction intentionally uses Java's wrapping int arithmetic, like native entity tickCount.
    // A gap greater than half its range is treated as overdue, never as authority to keep deferring.
    private static long elapsed(int now, int then) { return Integer.toUnsignedLong(now - then); }
    private void move(Member member, Scope destination) {
        if (member.scope.equals(destination)) return;
        detach(member);
        member.scope = destination;
        attach(member); // Preserve pending deadline and same-tick gate across every move.
    }
    private void attach(Member member) {
        buckets.compute(member.scope, (key, bucket) -> {
            if (bucket == null) bucket = new Bucket();
            int phase = bucket.smallest();
            member.phase = phase;
            bucket.phases.get(phase).put(member.id, member);
            bucket.size++;
            return bucket;
        });
    }
    private void detach(Member member) {
        buckets.compute(member.scope, (key, bucket) -> {
            if (bucket == null || bucket.phases.get(member.phase).remove(member.id) != member)
                throw new IllegalStateException("mob decision membership lost: " + member.id);
            bucket.size--;
            int small = bucket.smallest(), large = bucket.largest();
            if (bucket.phases.get(large).size() - bucket.phases.get(small).size() > 1) {
                Member moved = bucket.phases.get(large).pollFirstEntry().getValue();
                moved.phase = small;
                bucket.phases.get(small).put(moved.id, moved);
            }
            return bucket.size == 0 ? null : bucket;
        });
    }
    /** Diagnostics only, outside the regional hot path. No native entity lookup. */
    public Snapshot snapshot() {
        List<MemberView> views = members.values().stream().map(m -> new MemberView(m.id, m.nativeIncarnation,
            m.registryIncarnation, m.scope, m.phase, m.pending, m.firstDeferred, m.allowed, m.deferred, m.deadlines))
            .sorted(Comparator.comparing(MemberView::id)).toList();
        return new Snapshot(settings.enabled, settings.threshold, views.size(), buckets.size(),
            allowed.sum(), deferred.sum(), deadlines.sum(), views);
    }
    /** The existing terminal lifetime invokes this after owner work has settled. */
    public void close() { closed = true; members.clear(); buckets.clear(); }
    public record MemberView(String id, long incarnation, long registryIncarnation, Scope scope, int phase,
                             boolean pending, int firstDeferred, long allowed, long deferred, long deadlines) { }
    public record Snapshot(boolean enabled, int threshold, int memberships, int scopes,
                           long allowed, long deferred, long deadlines, List<MemberView> members) { }
    private static final class Member {
        final String id;
        final long nativeIncarnation, registryIncarnation;
        Scope scope;
        volatile int phase;
        boolean pending, hasTick, allow;
        int firstDeferred, tick;
        long allowed, deferred, deadlines;
        Member(String id, long nativeIncarnation, long registryIncarnation, Scope scope) {
            this.id = id; this.nativeIncarnation = nativeIncarnation;
            this.registryIncarnation = registryIncarnation; this.scope = scope;
        }
    }
    private static final class Bucket {
        final List<LinkedHashMap<String, Member>> phases = List.of(new LinkedHashMap<>(), new LinkedHashMap<>(),
            new LinkedHashMap<>(), new LinkedHashMap<>());
        volatile int size;
        int smallest() { int result = 0; for (int i = 1; i < COHORTS; i++)
            if (phases.get(i).size() < phases.get(result).size()) result = i; return result; }
        int largest() { int result = 0; for (int i = 1; i < COHORTS; i++)
            if (phases.get(i).size() > phases.get(result).size()) result = i; return result; }
    }
}
