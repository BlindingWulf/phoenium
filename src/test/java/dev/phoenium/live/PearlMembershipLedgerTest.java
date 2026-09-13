package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PearlMembershipLedgerTest {
    private static final WorldId OVERWORLD = new WorldId("minecraft:overworld");
    private static final WorldId NETHER = new WorldId("minecraft:the_nether");
    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test void disabledProfileIsInertButRetainsDerivedAssociationFacts() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(PearlMembershipLedger.Settings.disabled());
        PearlMembershipLedger.Scope scope = new PearlMembershipLedger.Scope(OVERWORLD, ALICE);

        PearlMembershipLedger.Admission first = ledger.admit(scope, "pearl-a", 1);
        PearlMembershipLedger.Admission repeated = ledger.admit(scope, "pearl-a", 1);
        PearlMembershipLedger.Admission second = ledger.admit(scope, "pearl-b", 1);

        assertTrue(first.inserted());
        assertFalse(repeated.inserted());
        assertEquals(first.membership(), repeated.membership());
        assertTrue(second.cleanup().isEmpty());
        assertEquals(2, ledger.membershipCount());
        assertEquals(1, ledger.scopeCount());
    }

    @Test void capIsScopedToWorldAndPlayerAndSelectsOldestExcessExactlyOnce() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(new PearlMembershipLedger.Settings(true, 2));
        PearlMembershipLedger.Scope overworldAlice = new PearlMembershipLedger.Scope(OVERWORLD, ALICE);
        PearlMembershipLedger.Scope overworldBob = new PearlMembershipLedger.Scope(OVERWORLD, BOB);
        PearlMembershipLedger.Scope netherAlice = new PearlMembershipLedger.Scope(NETHER, ALICE);

        PearlMembershipLedger.Membership old = ledger.admit(overworldAlice, "pearl-old", 1).membership();
        ledger.admit(overworldAlice, "pearl-middle", 1);
        PearlMembershipLedger.Admission third = ledger.admit(overworldAlice, "pearl-new", 1);
        assertEquals(List.of(old), third.cleanup());
        assertTrue(ledger.admit(overworldAlice, "pearl-new", 1).cleanup().isEmpty());
        assertEquals("pearl-middle", ledger.admit(overworldAlice, "pearl-latest", 1).cleanup().getFirst().objectId());

        ledger.admit(overworldBob, "bob-one", 1);
        ledger.admit(overworldBob, "bob-two", 1);
        assertTrue(ledger.admit(overworldBob, "bob-three", 1).cleanup().getFirst().objectId().startsWith("bob-"));
        ledger.admit(netherAlice, "nether-one", 1);
        ledger.admit(netherAlice, "nether-two", 1);
        assertTrue(ledger.admit(netherAlice, "nether-three", 1).cleanup().getFirst().objectId().startsWith("nether-"));
    }

    @Test void exactIncarnationRetirementCannotEraseAReusedPearlUuid() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(PearlMembershipLedger.Settings.disabled());
        PearlMembershipLedger.Scope scope = new PearlMembershipLedger.Scope(OVERWORLD, ALICE);
        ledger.admit(scope, "reused", 1);
        PearlMembershipLedger.Membership replacement = ledger.admit(scope, "reused", 2).membership();

        assertTrue(ledger.retire("reused", 1));
        assertTrue(ledger.isCurrent(replacement));
        assertEquals(1, ledger.membershipCount());
        assertTrue(ledger.retire("reused", 2));
        assertEquals(0, ledger.membershipCount());
    }

    @Test void markedDisconnectRebindPreservesSequenceButUsesTheNewIncarnation() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(PearlMembershipLedger.Settings.disabled());
        PearlMembershipLedger.Scope scope = new PearlMembershipLedger.Scope(OVERWORLD, ALICE);
        PearlMembershipLedger.Membership old = ledger.admit(scope, "disconnecting", 1).membership();

        assertTrue(ledger.preserveForReconnect("disconnecting", 1));
        PearlMembershipLedger.Admission rebound = ledger.admit(scope, "disconnecting", 2);

        assertTrue(rebound.inserted());
        assertEquals(old.admissionSequence(), rebound.membership().admissionSequence());
        assertFalse(ledger.isCurrent(old));
        assertTrue(ledger.isCurrent(rebound.membership()));
        assertEquals(1, ledger.membershipCount());
    }

    @Test void unmarkedDifferentIncarnationRemainsSeparateAndExact() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(PearlMembershipLedger.Settings.disabled());
        PearlMembershipLedger.Scope scope = new PearlMembershipLedger.Scope(OVERWORLD, ALICE);
        ledger.admit(scope, "duplicate", 1);

        PearlMembershipLedger.Membership replacement = ledger.admit(scope, "duplicate", 2).membership();
        assertEquals(2, ledger.membershipCount());
        assertTrue(ledger.isCurrent(replacement));
        assertTrue(ledger.retire("duplicate", 1));
        assertTrue(ledger.isCurrent(replacement));
    }

    @Test void queuedCleanupIsIdempotentAndSettlementIsExact() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(new PearlMembershipLedger.Settings(true, 1));
        PearlMembershipLedger.Scope scope = new PearlMembershipLedger.Scope(OVERWORLD, ALICE);
        PearlMembershipLedger.Membership first = ledger.admit(scope, "first", 1).membership();
        PearlMembershipLedger.Membership second = ledger.admit(scope, "second", 1).membership();

        assertEquals(List.of(first), ledger.snapshot(scope).subList(0, 1));
        assertFalse(ledger.settleCleanup(new PearlMembershipLedger.Membership(scope, "first", 99, 3)));
        assertTrue(ledger.isCurrent(first));
        assertTrue(ledger.settleCleanup(first));
        assertFalse(ledger.isCurrent(first));
        assertTrue(ledger.isCurrent(second));
    }

    @Test void restartReconstructionUsesStableIdentityOrderInsteadOfNativeIterationOrder() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(PearlMembershipLedger.Settings.disabled());
        PearlMembershipLedger.Scope scope = new PearlMembershipLedger.Scope(OVERWORLD, ALICE);
        ledger.admit(scope, "z-last", 1);
        ledger.admit(scope, "a-first", 1);
        ledger.admit(new PearlMembershipLedger.Scope(NETHER, BOB), "nether", 1);

        assertTrue(ledger.reconstructDeterministically().isEmpty());
        List<PearlMembershipLedger.Membership> entries = ledger.snapshot(scope);
        assertEquals(List.of("a-first", "z-last"), entries.stream().map(PearlMembershipLedger.Membership::objectId).toList());
        assertEquals(List.of(1L, 2L), entries.stream().map(PearlMembershipLedger.Membership::admissionSequence).toList());
    }

    @Test void disconnectHasNoImplicitRetirementOperation() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(PearlMembershipLedger.Settings.disabled());
        PearlMembershipLedger.Scope scope = new PearlMembershipLedger.Scope(OVERWORLD, ALICE);
        PearlMembershipLedger.Membership membership = ledger.admit(scope, "still-live", 4).membership();

        // A player disconnect is intentionally not represented as a ledger mutation.
        assertTrue(ledger.isCurrent(membership));
        assertEquals(1, ledger.membershipCount());
    }

    @Test void closeClearsDerivedStateAndLeavesPostStopDiagnosticsSafe() {
        PearlMembershipLedger ledger = new PearlMembershipLedger(PearlMembershipLedger.Settings.disabled());
        ledger.admit(new PearlMembershipLedger.Scope(OVERWORLD, ALICE), "closed", 1);
        ledger.close();

        assertEquals(0, ledger.membershipCount());
        assertEquals(0, ledger.scopeCount());
    }
}
