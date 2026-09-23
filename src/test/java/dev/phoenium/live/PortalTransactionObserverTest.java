package dev.phoenium.live;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.phoenium.live.PortalTransactionObserver.*;
import static org.junit.jupiter.api.Assertions.*;

class PortalTransactionObserverTest {
    private static Identity fireball(long generation) {
        return new Identity(generation, UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "minecraft:fireball", 17, 9, "NETHER_RETURN", "minecraft:the_nether", "minecraft:overworld",
            "nether:0,0", "overworld:0,0", "CANDIDATE_SHA", "TEST_SOURCE_SHA", Profile.CROSS_DIM_ENTITY);
    }
    private static void event(PortalTransactionObserver observer, Identity id, Phase phase, Owner owner) {
        observer.observe(Event.now(id, phase, owner, owner.name(), "control"));
    }
    private static void completeFireball(PortalTransactionObserver observer, Identity id) {
        event(observer, id, Phase.CAPTURE, Owner.SOURCE);
        event(observer, id, Phase.SOURCE_PREFIX, Owner.SOURCE);
        event(observer, id, Phase.SOURCE_UNWOUND, Owner.NONE);
        event(observer, id, Phase.DESTINATION_SEARCH, Owner.NONE);
        event(observer, id, Phase.DESTINATION_READY, Owner.NONE);
        event(observer, id, Phase.SOURCE_RETIRE, Owner.SOURCE);
        event(observer, id, Phase.DESTINATION_CONSTRUCT, Owner.DESTINATION);
        event(observer, id, Phase.RESTORE, Owner.SOURCE);
        event(observer, id, Phase.RESTORE_DESTINATION, Owner.DESTINATION);
        event(observer, id, Phase.DESTINATION_INSERT, Owner.DESTINATION);
        event(observer, id, Phase.ADDRESSABLE, Owner.DESTINATION);
        event(observer, id, Phase.TRACKING, Owner.DESTINATION);
        event(observer, id, Phase.CALLBACK, Owner.DESTINATION);
        event(observer, id, Phase.SUFFIX, Owner.SOURCE);
        event(observer, id, Phase.SETTLED, Owner.NONE);
    }

    @Test void completeCrossDimensionTraceSettlesOnceWithBoundedHistory() {
        var observer = new PortalTransactionObserver(5);
        completeFireball(observer, fireball(1));
        observer.assertClean();
        assertEquals(15, observer.eventCount());
        assertEquals(10, observer.droppedEvents());
        assertEquals(5, observer.recent().size());
        assertEquals(1, observer.transactionCount());
    }

    @Test void wrongOwnerTrackingFailsEvenIfNativeInsertionWouldSucceed() {
        var observer = new PortalTransactionObserver(32);
        Identity id = fireball(2);
        event(observer, id, Phase.CAPTURE, Owner.SOURCE);
        event(observer, id, Phase.SOURCE_UNWOUND, Owner.NONE);
        event(observer, id, Phase.SOURCE_RETIRE, Owner.SOURCE);
        event(observer, id, Phase.DESTINATION_INSERT, Owner.DESTINATION);
        event(observer, id, Phase.ADDRESSABLE, Owner.DESTINATION);
        event(observer, id, Phase.TRACKING, Owner.SOURCE);
        assertNotNull(observer.firstViolation());
        assertTrue(observer.firstViolation().reason().contains("outside destination owner"));
        assertThrows(AssertionError.class, observer::assertClean);
    }

    @Test void unpublishedReplacementRestoreRequiresSourceOwner() {
        var observer = new PortalTransactionObserver(8);
        Identity id = fireball(6);
        event(observer, id, Phase.CAPTURE, Owner.SOURCE);
        event(observer, id, Phase.RESTORE, Owner.DESTINATION);
        assertEquals(Phase.RESTORE, observer.firstViolation().phase());
        assertTrue(observer.firstViolation().reason().contains("outside source owner"));
    }

    @Test void replacementLoadRequiresDestinationOwner() {
        var observer = new PortalTransactionObserver(8);
        Identity id = fireball(7);
        event(observer, id, Phase.CAPTURE, Owner.SOURCE);
        event(observer, id, Phase.RESTORE_DESTINATION, Owner.SOURCE);
        assertEquals(Phase.RESTORE_DESTINATION, observer.firstViolation().phase());
        assertTrue(observer.firstViolation().reason().contains("outside destination owner"));
    }

    @Test void simulatedUnretainedFireballNativePathFailsImmediately() {
        var observer = new PortalTransactionObserver(8);
        Identity id = fireball(3);
        event(observer, id, Phase.CAPTURE, Owner.SOURCE);
        event(observer, id, Phase.NATIVE_UNRETAINED, Owner.SOURCE);
        assertEquals(Phase.NATIVE_UNRETAINED, observer.firstViolation().phase());
        assertThrows(AssertionError.class, observer::assertClean);
    }

    @Test void sourceOwnedDestinationWaitAndSkippedPhaseFail() {
        var blocked = new PortalTransactionObserver(8);
        Identity id = fireball(4);
        event(blocked, id, Phase.CAPTURE, Owner.SOURCE);
        event(blocked, id, Phase.SYNCHRONOUS_WAIT, Owner.SOURCE);
        assertTrue(blocked.firstViolation().reason().contains("wait held owner"));

        var skipped = new PortalTransactionObserver(16);
        event(skipped, id, Phase.CAPTURE, Owner.SOURCE);
        event(skipped, id, Phase.SOURCE_UNWOUND, Owner.NONE);
        event(skipped, id, Phase.SETTLED, Owner.NONE);
        assertTrue(skipped.firstViolation().reason().contains("skipped required phases"));
        assertThrows(AssertionError.class, skipped::assertClean);
    }

    @Test void repeatedSettlementCannotHideBehindASecondSuccess() {
        var observer = new PortalTransactionObserver(16);
        Identity id = new Identity(5, UUID.randomUUID(), "minecraft:ender_dragon", 1, 1,
            "END_ENTRY", "minecraft:overworld", "minecraft:the_end", "a", "b", "C", "T",
            Profile.NATIVE_REFUSAL);
        for (Phase phase : List.of(Phase.CAPTURE, Phase.SOURCE_UNWOUND, Phase.DESTINATION_SEARCH, Phase.SUFFIX, Phase.SETTLED))
            event(observer, id, phase, phase == Phase.CAPTURE || phase == Phase.SUFFIX ? Owner.SOURCE : Owner.NONE);
        observer.assertClean();
        event(observer, id, Phase.SETTLED, Owner.NONE);
        assertTrue(observer.firstViolation().reason().contains("followed terminal"));
    }
}
