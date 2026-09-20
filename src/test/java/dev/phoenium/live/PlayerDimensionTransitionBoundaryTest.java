package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.WorldId;
import org.junit.jupiter.api.Test;

final class PlayerDimensionTransitionBoundaryTest {
    private static final WorldId NETHER = new WorldId("minecraft:the_nether");
    private static final WorldId OVERWORLD = new WorldId("minecraft:overworld");
    private static final PlayerDimensionTransitionBoundary.SourceIdentity SOURCE =
        new PlayerDimensionTransitionBoundary.SourceIdentity("player", 41, 7, NETHER, OVERWORLD);

    @Test void transitionGenerationIsMonotonicForRepeatedExactPlayer() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var first = boundary.begin(SOURCE);
        boundary.cancel(first);
        var second = boundary.begin(SOURCE);
        assertTrue(second.generation() > first.generation());
        assertFalse(boundary.cancel(first));
        assertTrue(boundary.isCurrent(second));
    }

    @Test void duplicateBeginAndDuplicateSourceRemovalAreRejected() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var token = boundary.begin(SOURCE);
        assertThrows(IllegalStateException.class, () -> boundary.begin(SOURCE));
        boundary.authorizeSourceRemoval(token, SOURCE);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeSourceRemoval(token, SOURCE));
    }

    @Test void expectedDimensionRemovalDoesNotCancelTransition() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var token = boundary.begin(SOURCE);
        var removal = boundary.authorizeSourceRemoval(token, SOURCE);
        assertTrue(boundary.recognizesExpectedRemoval(removal, SOURCE));
        assertTrue(boundary.isCurrent(token));
    }

    @Test void differentNativeIncarnationCannotAuthorizeSourceRemoval() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var token = boundary.begin(SOURCE);
        var other = new PlayerDimensionTransitionBoundary.SourceIdentity(
            "player", 42, 7, NETHER, OVERWORLD);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeSourceRemoval(token, other));
    }

    @Test void sameUuidDifferentOwnershipIncarnationCannotAuthorizeSourceRemoval() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var token = boundary.begin(SOURCE);
        var other = new PlayerDimensionTransitionBoundary.SourceIdentity(
            "player", 41, 8, NETHER, OVERWORLD);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeSourceRemoval(token, other));
    }

    @Test void destinationCannotBeginBeforeSourceRetires() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var token = boundary.begin(SOURCE);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeDestinationAdmission(token));
        var removal = boundary.authorizeSourceRemoval(token, SOURCE);
        assertThrows(IllegalStateException.class, () -> boundary.authorizeDestinationAdmission(token));
        boundary.sourceRetired(removal);
        assertDoesNotThrow(() -> boundary.authorizeDestinationAdmission(token));
    }

    @Test void destinationMustPreserveExactNativeAndOwnershipIncarnations() {
        var nativeFixture = retired();
        var nativeAdmission = nativeFixture.boundary.authorizeDestinationAdmission(nativeFixture.token);
        assertThrows(IllegalStateException.class, () -> nativeFixture.boundary.destinationAdmitted(nativeAdmission,
            new PlayerDimensionTransitionBoundary.DestinationIdentity("player", 42, 7, OVERWORLD)));

        var registryFixture = retired();
        var registryAdmission = registryFixture.boundary.authorizeDestinationAdmission(registryFixture.token);
        assertThrows(IllegalStateException.class, () -> registryFixture.boundary.destinationAdmitted(registryAdmission,
            new PlayerDimensionTransitionBoundary.DestinationIdentity("player", 41, 8, OVERWORLD)));
    }

    @Test void destinationWorldAndUuidCannotChange() {
        var uuidFixture = retired();
        var uuidAdmission = uuidFixture.boundary.authorizeDestinationAdmission(uuidFixture.token);
        assertThrows(IllegalStateException.class, () -> uuidFixture.boundary.destinationAdmitted(uuidAdmission,
            new PlayerDimensionTransitionBoundary.DestinationIdentity("other", 41, 7, OVERWORLD)));

        var worldFixture = retired();
        var worldAdmission = worldFixture.boundary.authorizeDestinationAdmission(worldFixture.token);
        assertThrows(IllegalStateException.class, () -> worldFixture.boundary.destinationAdmitted(worldAdmission,
            new PlayerDimensionTransitionBoundary.DestinationIdentity("player", 41, 7, NETHER)));
    }

    @Test void destinationPublicationPrecedesContinuationAndContinuationIsExactlyOnce() {
        var fixture = retired();
        assertThrows(IllegalStateException.class,
            () -> fixture.boundary.authorizeContinuation(fixture.token));
        var admission = fixture.boundary.authorizeDestinationAdmission(fixture.token);
        fixture.boundary.destinationAdmitted(admission,
            new PlayerDimensionTransitionBoundary.DestinationIdentity("player", 41, 7, OVERWORLD));
        var continuation = fixture.boundary.authorizeContinuation(fixture.token);
        assertThrows(IllegalStateException.class,
            () -> fixture.boundary.authorizeContinuation(fixture.token));
        fixture.boundary.settle(continuation);
        assertEquals(0, fixture.boundary.pendingCount());
        assertThrows(IllegalStateException.class, () -> fixture.boundary.settle(continuation));
    }

    @Test void externalRemovalRetiresGenerationAndLateDestinationWorkIsInert() {
        var fixture = retired();
        assertTrue(fixture.boundary.cancel(fixture.token));
        assertFalse(fixture.boundary.cancel(fixture.token));
        assertEquals(PlayerDimensionTransitionBoundary.Phase.RETIRED,
            fixture.boundary.phase(fixture.token));
        assertThrows(IllegalStateException.class,
            () -> fixture.boundary.authorizeDestinationAdmission(fixture.token));
    }

    @Test void shutdownRetiresPendingTransitionAndRejectsNewOnes() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var token = boundary.begin(SOURCE);
        boundary.close();
        assertFalse(boundary.isCurrent(token));
        assertEquals(0, boundary.pendingCount());
        assertThrows(IllegalStateException.class, () -> boundary.begin(SOURCE));
    }

    @Test void sameDimensionTransitionIsNotRepresentable() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var same = new PlayerDimensionTransitionBoundary.SourceIdentity(
            "player", 41, 7, NETHER, NETHER);
        assertThrows(IllegalArgumentException.class, () -> boundary.begin(same));
    }

    private static Fixture retired() {
        var boundary = new PlayerDimensionTransitionBoundary();
        var token = boundary.begin(SOURCE);
        var removal = boundary.authorizeSourceRemoval(token, SOURCE);
        boundary.sourceRetired(removal);
        return new Fixture(boundary, token);
    }

    private record Fixture(PlayerDimensionTransitionBoundary boundary,
                           PlayerDimensionTransitionBoundary.Token token) { }
}
