package dev.phoenium.stage7;

import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Test-only executable specification for the Panda-derived Stage 7 protection policy.
 *
 * <p>This is deliberately not wired into the live runtime.  The policy has to be stable before
 * Phoenium chooses any Minecraft integration seam or adds any new derived lifecycle ledger.</p>
 */
final class NativeProtectionPolicyModel {
    private NativeProtectionPolicyModel() { }

    record Settings(boolean projectileCapEnabled, int projectileCap,
                    boolean pearlCapEnabled, int pearlCap,
                    boolean staggeringEnabled, int minimumMobs, int minimumVehicles,
                    int targetEntitiesPerTick, int maxStride) {
        Settings {
            if (projectileCap <= 0) throw new IllegalArgumentException("projectile cap must be positive");
            if (pearlCap <= 0) throw new IllegalArgumentException("pearl cap must be positive");
            if (minimumMobs < 0 || minimumVehicles < 0) throw new IllegalArgumentException("negative stagger threshold");
            if (targetEntitiesPerTick <= 0) throw new IllegalArgumentException("target entities per tick must be positive");
            if (maxStride <= 0) throw new IllegalArgumentException("max stride must be positive");
        }

        static Settings disabled() {
            // Numeric values are intentionally non-policy placeholders while every feature is off.
            // Enabling a protection requires an explicit accepted configuration.
            return new Settings(false, 1, false, 1, false, 0, 0, 1, 1);
        }
    }

    /** Current Phoenium ownership region plus projectile type; RegionId already carries WorldId. */
    record ProjectileScope(RegionId region, String projectileType) {
        ProjectileScope {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(projectileType, "projectileType");
            if (projectileType.isBlank()) throw new IllegalArgumentException("projectile type is blank");
        }
    }

    /** Per-player, per-dimension pearl scope.  It intentionally does not contain a region owner. */
    record PearlScope(WorldId world, UUID playerId) {
        PearlScope {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    /** Identity plus incarnation prevents stale cleanup from deleting a later reused binding. */
    record Member(String objectId, long incarnation, long admissionSequence) {
        Member {
            Objects.requireNonNull(objectId, "objectId");
            if (objectId.isBlank()) throw new IllegalArgumentException("object id is blank");
            if (incarnation < 0) throw new IllegalArgumentException("negative incarnation");
            if (admissionSequence < 0) throw new IllegalArgumentException("negative admission sequence");
        }
    }

    enum StaggerClass { PLAYER, CONTROLLED_VEHICLE, MONSTER, PEACEFUL, VEHICLE, OTHER }

    /**
     * Exact deterministic overflow rule: keep the newest {@code cap} members and evict the oldest
     * excess entries, ordered by admission sequence and then stable identity.
     */
    static List<Member> oldestExcess(List<Member> active, int cap) {
        Objects.requireNonNull(active, "active");
        if (cap <= 0) throw new IllegalArgumentException("cap must be positive");
        if (active.size() <= cap) return List.of();
        ArrayList<Member> ordered = new ArrayList<>(active);
        ordered.sort(Comparator.comparingLong(Member::admissionSequence)
            .thenComparing(Member::objectId)
            .thenComparingLong(Member::incarnation));
        return List.copyOf(ordered.subList(0, ordered.size() - cap));
    }

    /** Cleanup matches the exact object incarnation rather than a reusable identity alone. */
    static List<Member> removeExact(List<Member> active, String objectId, long incarnation) {
        ArrayList<Member> retained = new ArrayList<>();
        for (Member member : active) {
            if (!member.objectId().equals(objectId) || member.incarnation() != incarnation) retained.add(member);
        }
        return List.copyOf(retained);
    }

    /**
     * Policy-only stride.  Exempt classes and counts at/below their threshold always retain native
     * every-tick behavior.  Enabled staggering is bounded so every admitted entity has a finite
     * service interval.
     */
    static int stride(Settings settings, StaggerClass kind, int nearbyCount) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(kind, "kind");
        if (nearbyCount < 0) throw new IllegalArgumentException("negative nearby count");
        if (!settings.staggeringEnabled()) return 1;
        if (kind == StaggerClass.PLAYER || kind == StaggerClass.CONTROLLED_VEHICLE || kind == StaggerClass.OTHER) return 1;
        int threshold = kind == StaggerClass.VEHICLE ? settings.minimumVehicles() : settings.minimumMobs();
        if (nearbyCount <= threshold) return 1;
        int raw = Math.max(1, (nearbyCount + settings.targetEntitiesPerTick() - 1) / settings.targetEntitiesPerTick());
        return Math.min(settings.maxStride(), raw);
    }

    /** Stable UUID phase gives every staggered entity exactly one slot per stride-sized window. */
    static boolean shouldRun(long tick, UUID stableId, int stride) {
        Objects.requireNonNull(stableId, "stableId");
        if (stride <= 0) throw new IllegalArgumentException("stride must be positive");
        if (stride == 1) return true;
        int phase = Math.floorMod(stableId.hashCode(), stride);
        return Math.floorMod(tick, stride) == phase;
    }
}
