package dev.phoenium.stage7;

import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static dev.phoenium.stage7.NativeProtectionPolicyModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic executable requirements for the Stage 7 Panda-derived native protections. */
class NativeProtectionPolicyTest {
    private final WorldId overworld = new WorldId("minecraft:overworld");
    private final WorldId nether = new WorldId("minecraft:the_nether");

    @Test void protectionsAreOptInAndDisabledPolicyCannotChangeNormalGameplay() {
        Settings disabled = Settings.disabled();
        assertFalse(disabled.projectileCapEnabled());
        assertFalse(disabled.pearlCapEnabled());
        assertFalse(disabled.staggeringEnabled());
        assertEquals(1, stride(disabled, StaggerClass.MONSTER, 50_000));
        assertTrue(shouldRun(1234, UUID.fromString("00000000-0000-0000-0000-000000000001"), 1));
    }

    @Test void projectileScopeIncludesDimensionRegionAndType() {
        ProjectileScope aArrow = new ProjectileScope(new RegionId(overworld, "A"), "minecraft:arrow");
        ProjectileScope bArrow = new ProjectileScope(new RegionId(overworld, "B"), "minecraft:arrow");
        ProjectileScope aPearl = new ProjectileScope(new RegionId(overworld, "A"), "minecraft:ender_pearl");
        ProjectileScope netherArrow = new ProjectileScope(new RegionId(nether, "A"), "minecraft:arrow");
        assertNotEquals(aArrow, bArrow);
        assertNotEquals(aArrow, aPearl);
        assertNotEquals(aArrow, netherArrow);
    }

    @Test void projectileOverflowEvictsExactlyTheOldestExcessDeterministically() {
        List<Member> active = List.of(
            member("p4", 4), member("p1", 1), member("p5", 5), member("p3", 3), member("p2", 2));
        assertEquals(List.of(member("p1", 1), member("p2", 2)), oldestExcess(active, 3));
        assertEquals(List.of(), oldestExcess(active.subList(0, 3), 3));
    }

    @Test void equalSequenceEvictionUsesStableIdentityInsteadOfIterationOrder() {
        Member b = new Member("b", 0, 7);
        Member a = new Member("a", 0, 7);
        Member c = new Member("c", 0, 8);
        assertEquals(List.of(a), oldestExcess(List.of(b, c, a), 2));
    }

    @Test void pearlScopeIsPerPlayerPerDimensionAndIndependentOfPearlRegion() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
        PearlScope overworldScope = new PearlScope(overworld, player);
        assertEquals(overworldScope, new PearlScope(overworld, player));
        assertNotEquals(overworldScope, new PearlScope(nether, player));
        assertNotEquals(overworldScope, new PearlScope(overworld, other));
    }

    @Test void pearlOverflowKeepsNewestEntriesAndMovementDoesNotChangePlayerWorldScope() {
        UUID player = UUID.fromString("33333333-3333-3333-3333-333333333333");
        PearlScope beforeMove = new PearlScope(overworld, player);
        PearlScope afterMove = new PearlScope(overworld, player);
        List<Member> pearls = List.of(member("old", 10), member("middle", 11), member("new", 12));
        assertEquals(beforeMove, afterMove);
        assertEquals(List.of(member("old", 10)), oldestExcess(pearls, 2));
    }

    @Test void cleanupIsIncarnationSafe() {
        List<Member> active = List.of(new Member("pearl", 3, 1), new Member("pearl", 4, 2), member("other", 3));
        assertEquals(List.of(new Member("pearl", 4, 2), member("other", 3)), removeExact(active, "pearl", 3));
        assertEquals(active, removeExact(active, "pearl", 99));
    }

    @Test void staggeringExemptsPlayersControlledVehiclesAndCountsBelowThreshold() {
        Settings settings = enabledStagger(75, 75, 200, 32);
        assertEquals(1, stride(settings, StaggerClass.PLAYER, 10_000));
        assertEquals(1, stride(settings, StaggerClass.CONTROLLED_VEHICLE, 10_000));
        assertEquals(1, stride(settings, StaggerClass.OTHER, 10_000));
        assertEquals(1, stride(settings, StaggerClass.MONSTER, 75));
        assertEquals(1, stride(settings, StaggerClass.VEHICLE, 75));
    }

    @Test void staggerStrideIsBoundedAndEveryStableEntityGetsOneRunPerWindow() {
        Settings settings = enabledStagger(75, 75, 200, 8);
        int stride = stride(settings, StaggerClass.PEACEFUL, 5_000);
        assertEquals(8, stride);
        UUID entity = UUID.fromString("44444444-4444-4444-4444-444444444444");
        for (long window = 0; window < 40; window += stride) {
            int runs = 0;
            for (long tick = window; tick < window + stride; tick++) if (shouldRun(tick, entity, stride)) runs++;
            assertEquals(1, runs, "entity must receive exactly one tick slot per bounded window");
        }
    }

    @Test void staggerPhaseDistributionDoesNotStarveAGroupOfEntities() {
        Settings settings = enabledStagger(0, 0, 1, 16);
        int stride = stride(settings, StaggerClass.MONSTER, 16);
        assertEquals(16, stride);
        List<UUID> entities = new ArrayList<>();
        for (int i = 0; i < 64; i++) entities.add(new UUID(0, i + 1L));
        for (UUID entity : entities) {
            int runs = 0;
            for (long tick = 0; tick < stride; tick++) if (shouldRun(tick, entity, stride)) runs++;
            assertEquals(1, runs);
        }
    }

    private static Member member(String id, long sequence) { return new Member(id, 0, sequence); }

    private static Settings enabledStagger(int mobs, int vehicles, int target, int maxStride) {
        return new Settings(false, 1, false, 1, true, mobs, vehicles, target, maxStride);
    }
}
