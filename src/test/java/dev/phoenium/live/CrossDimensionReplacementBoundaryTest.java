package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class CrossDimensionReplacementBoundaryTest {
    private static final CrossDimensionReplacementBoundary.SourceIdentity SOURCE =
        new CrossDimensionReplacementBoundary.SourceIdentity("pearl", 41, 7);

    @Test void pendingTransitionCreatedOncePerExactSource() {
        var boundary = new CrossDimensionReplacementBoundary();
        var token = boundary.begin(SOURCE);
        assertEquals(1, boundary.pendingCount());
        assertEquals(CrossDimensionReplacementBoundary.Phase.CAPTURED, boundary.phase(token));
        assertThrows(IllegalStateException.class, () -> boundary.begin(SOURCE));
    }

    @Test void preparedGenerationCannotBeAppliedTwice() {
        var boundary = new CrossDimensionReplacementBoundary();
        var token = boundary.begin(SOURCE);
        boundary.prepared(token);
        assertEquals(CrossDimensionReplacementBoundary.Phase.PREPARED, boundary.phase(token));
        assertThrows(IllegalStateException.class, () -> boundary.prepared(token));
    }

    @Test void expectedCommittedRemovalIsRecognizedOnlyInItsAuthorizedPhase() {
        var boundary = new CrossDimensionReplacementBoundary();
        var token = boundary.begin(SOURCE);
        boundary.prepared(token);
        var removal = boundary.authorizeSourceRemoval(token, SOURCE);
        assertTrue(boundary.recognizesExpectedRemoval(removal, SOURCE));
        assertFalse(boundary.recognizesExpectedRemoval(removal,
            new CrossDimensionReplacementBoundary.SourceIdentity("pearl", 42, 7)));
        boundary.sourceRetired(removal);
        assertFalse(boundary.recognizesExpectedRemoval(removal, SOURCE));
    }

    @Test void externalRemovalCancelsPendingTransitionAndLateCompletionIsInert() {
        var boundary = new CrossDimensionReplacementBoundary();
        var token = boundary.begin(SOURCE);
        assertTrue(boundary.cancel(token));
        assertEquals(0, boundary.pendingCount());
        assertFalse(boundary.cancel(token));
        assertThrows(IllegalStateException.class, () -> boundary.prepared(token));
    }

    @Test void sameUuidReplacementMustHaveNewNativeAndRegistryIncarnations() {
        var boundary = preparedAndRetired();
        var token = onlyToken;
        var admission = boundary.authorizeDestinationAdmission(token);
        assertThrows(IllegalStateException.class, () -> boundary.destinationAdmitted(admission,
            new CrossDimensionReplacementBoundary.DestinationIdentity("pearl", 41, 8)));
    }

    @Test void destinationRegistryIncarnationMustAdvance() {
        var boundary = preparedAndRetired();
        var token = onlyToken;
        var admission = boundary.authorizeDestinationAdmission(token);
        assertThrows(IllegalStateException.class, () -> boundary.destinationAdmitted(admission,
            new CrossDimensionReplacementBoundary.DestinationIdentity("pearl", 42, 7)));
    }

    @Test void destinationUuidCannotChange() {
        var boundary = preparedAndRetired();
        var token = onlyToken;
        var admission = boundary.authorizeDestinationAdmission(token);
        assertThrows(IllegalStateException.class, () -> boundary.destinationAdmitted(admission,
            new CrossDimensionReplacementBoundary.DestinationIdentity("other", 42, 8)));
    }

    @Test void replacementContinuationExecutesAtMostOnce() {
        var boundary = preparedAndRetired();
        var token = onlyToken;
        var admission = boundary.authorizeDestinationAdmission(token);
        boundary.destinationAdmitted(admission,
            new CrossDimensionReplacementBoundary.DestinationIdentity("pearl", 42, 8));
        var continuation = boundary.authorizeReplacementContinuation(token);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeReplacementContinuation(token));
        boundary.settle(continuation);
        assertEquals(0, boundary.pendingCount());
        assertThrows(IllegalStateException.class, () -> boundary.settle(continuation));
    }

    @Test void unchangedSourceContinuationRetainsExactIncarnation() {
        var boundary = new CrossDimensionReplacementBoundary();
        var token = boundary.begin(SOURCE);
        boundary.prepared(token);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeUnchangedSourceContinuation(token,
            new CrossDimensionReplacementBoundary.SourceIdentity("pearl", 99, 7)));
        var continuation = boundary.authorizeUnchangedSourceContinuation(token, SOURCE);
        assertFalse(continuation.replaced());
        boundary.settle(continuation);
    }

    @Test void staleGenerationCannotAffectNewerSameIdentityTransition() {
        var boundary = new CrossDimensionReplacementBoundary();
        var stale = boundary.begin(SOURCE);
        boundary.cancel(stale);
        var current = boundary.begin(SOURCE);
        assertNotEquals(stale.generation(), current.generation());
        assertFalse(boundary.cancel(stale));
        assertTrue(boundary.isCurrent(current));
    }

    @Test void closeRetiresPendingTransitionsAndRejectsNewAdmission() {
        var boundary = new CrossDimensionReplacementBoundary();
        var token = boundary.begin(SOURCE);
        boundary.close();
        assertEquals(0, boundary.pendingCount());
        assertFalse(boundary.isCurrent(token));
        assertThrows(IllegalStateException.class, () -> boundary.begin(SOURCE));
    }

    @Test void sourceAndDestinationStagesAreStrictlySerial() {
        var boundary = new CrossDimensionReplacementBoundary();
        var token = boundary.begin(SOURCE);
        boundary.prepared(token);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeDestinationAdmission(token));
        var removal = boundary.authorizeSourceRemoval(token, SOURCE);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeDestinationAdmission(token));
        boundary.sourceRetired(removal);
        assertDoesNotThrow(() -> boundary.authorizeDestinationAdmission(token));
    }

    private CrossDimensionReplacementBoundary.Token onlyToken;
    private CrossDimensionReplacementBoundary preparedAndRetired() {
        var boundary = new CrossDimensionReplacementBoundary();
        onlyToken = boundary.begin(SOURCE);
        boundary.prepared(onlyToken);
        var removal = boundary.authorizeSourceRemoval(onlyToken, SOURCE);
        boundary.sourceRetired(removal);
        return boundary;
    }
}
