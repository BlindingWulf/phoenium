package dev.phoenium.chunk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** S9-D3: the cut's O(1) membership index keeps the original identity semantics and record value semantics. */
final class TrackingCutIndexTest {
    private static FullFinishingScoreboard.TrackingSession session(long id, UUID uuid) {
        return new FullFinishingScoreboard.TrackingSession(id, null, null, uuid, null);
    }

    @Test
    void membershipIsByExactReceiptIdentity() {
        UUID uuid = UUID.randomUUID();
        var first = session(1, uuid);
        var second = session(2, UUID.randomUUID());
        var cut = new FullFinishingScoreboard.TrackingCut(7, List.of(first, second));
        assertTrue(cut.contains(first));
        assertTrue(cut.contains(second));
        var equalButDistinct = session(1, uuid);
        assertEquals(first, equalButDistinct, "receipts are records");
        assertFalse(cut.contains(equalButDistinct), "an equal receipt from another cut is not this cut's receipt");
        assertFalse(cut.contains(null));
    }

    @Test
    void valueSemanticsAreThoseOfTheTwoComponentRecord() {
        UUID uuid = UUID.randomUUID();
        var a = new FullFinishingScoreboard.TrackingCut(3, List.of(session(1, uuid)));
        var b = new FullFinishingScoreboard.TrackingCut(3, List.of(session(1, uuid)));
        assertEquals(a, b, "equality ignores the derived identity index");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new FullFinishingScoreboard.TrackingCut(4, List.of(session(1, uuid))));
        assertEquals(List.of(session(1, uuid)), a.sessions());
        assertThrows(UnsupportedOperationException.class, () -> a.sessions().add(session(2, uuid)));
        assertThrows(UnsupportedOperationException.class, () -> a.members().clear());
    }
}
