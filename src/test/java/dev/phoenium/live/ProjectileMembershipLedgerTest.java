package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectileMembershipLedgerTest {
    private static final WorldId OVERWORLD = new WorldId("minecraft:overworld");
    private static final WorldId NETHER = new WorldId("minecraft:the_nether");
    private static final RegionId REGION_A = new RegionId(OVERWORLD, "A");
    private static final RegionId REGION_B = new RegionId(OVERWORLD, "B");
    private static final RegionId NETHER_A = new RegionId(NETHER, "A");
    private static final String ARROW = "minecraft:arrow";
    private static final String SNOWBALL = "minecraft:snowball";

    @Test void disabledProfileIsInertAndRepeatedAddressabilityIsIdempotent() {
        ProjectileMembershipLedger ledger = new ProjectileMembershipLedger(ProjectileMembershipLedger.Settings.disabled());
        ProjectileMembershipLedger.Scope scope = new ProjectileMembershipLedger.Scope(REGION_A, ARROW);

        ProjectileMembershipLedger.Admission first = ledger.admit(scope, "p1", 1);
        ProjectileMembershipLedger.Admission repeated = ledger.admit(scope, "p1", 1);
        ledger.admit(scope, "p2", 1);

        assertTrue(first.inserted());
        assertFalse(repeated.inserted());
        assertEquals(first.membership(), repeated.membership());
        assertTrue(repeated.cleanup().isEmpty());
        assertEquals(2, ledger.membershipCount());
    }

    @Test void capIsScopedToCurrentRegionWorldAndProjectileType() {
        ProjectileMembershipLedger ledger = new ProjectileMembershipLedger(
            new ProjectileMembershipLedger.Settings(true, 2));
        ProjectileMembershipLedger.Scope regionAArrows = new ProjectileMembershipLedger.Scope(REGION_A, ARROW);
        ProjectileMembershipLedger.Scope regionBArrows = new ProjectileMembershipLedger.Scope(REGION_B, ARROW);
        ProjectileMembershipLedger.Scope regionASnowballs = new ProjectileMembershipLedger.Scope(REGION_A, SNOWBALL);
        ProjectileMembershipLedger.Scope netherArrows = new ProjectileMembershipLedger.Scope(NETHER_A, ARROW);

        ProjectileMembershipLedger.Membership old = ledger.admit(regionAArrows, "old", 1).membership();
        ledger.admit(regionAArrows, "middle", 1);
        assertEquals(List.of(old), ledger.admit(regionAArrows, "new", 1).cleanup());
        assertTrue(ledger.settleCleanup(old));
        ledger.admit(regionBArrows, "b1", 1);
        ledger.admit(regionBArrows, "b2", 1);
        ProjectileMembershipLedger.Admission b3 = ledger.admit(regionBArrows, "b3", 1);
        assertEquals(1, b3.cleanup().size());
        assertTrue(ledger.settleCleanup(b3.cleanup().getFirst()));
        ledger.admit(regionASnowballs, "s1", 1);
        ledger.admit(regionASnowballs, "s2", 1);
        ledger.admit(netherArrows, "n1", 1);
        ledger.admit(netherArrows, "n2", 1);

        assertEquals(2, ledger.snapshot(regionBArrows).size());
        assertEquals(2, ledger.snapshot(regionASnowballs).size());
        assertEquals(2, ledger.snapshot(netherArrows).size());
        assertNotEquals(regionAArrows, regionBArrows);
        assertNotEquals(regionAArrows, netherArrows);
    }

    @Test void overflowSelectsOldestExcessExactlyOnceWithStableTies() {
        ProjectileMembershipLedger ledger = new ProjectileMembershipLedger(
            new ProjectileMembershipLedger.Settings(true, 2));
        ProjectileMembershipLedger.Scope scope = new ProjectileMembershipLedger.Scope(REGION_A, ARROW);
        ProjectileMembershipLedger.Membership a = ledger.admit(scope, "b", 1).membership();
        ProjectileMembershipLedger.Membership b = ledger.admit(scope, "a", 1).membership();
        ProjectileMembershipLedger.Admission third = ledger.admit(scope, "c", 1);

        assertEquals(List.of(a), third.cleanup());
        assertTrue(ledger.admit(scope, "c", 1).cleanup().isEmpty());
        assertTrue(ledger.isCurrent(b));
    }

    @Test void relocationPreservesIdentityAndAdmissionAgeThenSelectsDestinationOverflow() {
        ProjectileMembershipLedger ledger = new ProjectileMembershipLedger(
            new ProjectileMembershipLedger.Settings(true, 1));
        ProjectileMembershipLedger.Scope source = new ProjectileMembershipLedger.Scope(REGION_A, ARROW);
        ProjectileMembershipLedger.Scope destination = new ProjectileMembershipLedger.Scope(REGION_B, ARROW);
        ProjectileMembershipLedger.Membership moved = ledger.admit(source, "moved", 4).membership();
        ledger.admit(destination, "newer", 1);

        List<ProjectileMembershipLedger.Membership> cleanup = ledger.relocate(
            "moved", 4, REGION_A, REGION_B);
        assertEquals(1, cleanup.size());
        assertEquals("moved", cleanup.getFirst().objectId());
        assertEquals(REGION_B, cleanup.getFirst().scope().region());
        assertEquals(moved.admissionSequence(), cleanup.getFirst().admissionSequence());
        ProjectileMembershipLedger.Membership current = ledger.snapshot(destination).stream()
            .filter(value -> value.objectId().equals("moved")).findFirst().orElseThrow();
        assertEquals(REGION_B, current.scope().region());
        assertEquals(moved.admissionSequence(), current.admissionSequence());
        assertTrue(ledger.relocate("moved", 4, REGION_A, REGION_B).isEmpty());
    }

    @Test void exactIncarnationRetirementCannotEraseAReusedProjectileUuid() {
        ProjectileMembershipLedger ledger = new ProjectileMembershipLedger(ProjectileMembershipLedger.Settings.disabled());
        ProjectileMembershipLedger.Scope scope = new ProjectileMembershipLedger.Scope(REGION_A, ARROW);
        ledger.admit(scope, "reused", 1);
        ProjectileMembershipLedger.Membership replacement = ledger.admit(scope, "reused", 2).membership();

        assertTrue(ledger.retire("reused", 1));
        assertTrue(ledger.isCurrent(replacement));
        assertTrue(ledger.retire("reused", 2));
        assertEquals(0, ledger.membershipCount());
    }

    @Test void restartReconstructionUsesStableScopeAndIdentityOrder() {
        ProjectileMembershipLedger ledger = new ProjectileMembershipLedger(ProjectileMembershipLedger.Settings.disabled());
        ledger.admit(new ProjectileMembershipLedger.Scope(REGION_B, ARROW), "z", 1);
        ledger.admit(new ProjectileMembershipLedger.Scope(REGION_A, ARROW), "a", 1);
        ledger.admit(new ProjectileMembershipLedger.Scope(NETHER_A, SNOWBALL), "n", 1);

        assertTrue(ledger.reconstructDeterministically().isEmpty());
        assertEquals(List.of(1L), ledger.snapshot(new ProjectileMembershipLedger.Scope(REGION_A, ARROW))
            .stream().map(ProjectileMembershipLedger.Membership::admissionSequence).toList());
        assertEquals("a", ledger.snapshot(new ProjectileMembershipLedger.Scope(REGION_A, ARROW)).getFirst().objectId());
    }

    @Test void closeClearsDerivedState() {
        ProjectileMembershipLedger ledger = new ProjectileMembershipLedger(ProjectileMembershipLedger.Settings.disabled());
        ledger.admit(new ProjectileMembershipLedger.Scope(REGION_A, ARROW), "closed", 1);
        ledger.close();
        assertEquals(0, ledger.membershipCount());
        assertEquals(0, ledger.scopeCount());
    }
}
