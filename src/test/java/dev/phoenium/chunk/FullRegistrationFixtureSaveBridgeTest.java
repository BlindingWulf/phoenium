package dev.phoenium.chunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import dev.phoenium.save.RegionalSaveCoordinator;
import dev.phoenium.topology.RoutingKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.stub;
import static org.junit.jupiter.api.Assertions.*;

final class FullRegistrationFixtureSaveBridgeTest {
    @Test
    void onlyTheExactRegisteredLevelAndMapCanEnterTheIsolatedSaveAuthority() throws Exception {
        var fixture = new FullRegistrationFixture(true);
        try {
            var binding = FullRegistrationFixtureSaveBridge.find(fixture.world.level, fixture.fixtureMap);
            assertNotNull(binding);
            assertSame(fixture.authority, binding.authority());
            assertSame(fixture.saveCoordinator, binding.coordinator());
            assertSame(fixture.runtime, binding.runtime());

            ChunkMap foreignMap = stub(ChunkMap.class, List.of());
            ServerLevel foreignLevel = stub(ServerLevel.class, List.of());
            assertNull(FullRegistrationFixtureSaveBridge.find(fixture.world.level, foreignMap));
            assertNull(FullRegistrationFixtureSaveBridge.find(foreignLevel, fixture.fixtureMap));
            assertNull(FullRegistrationFixtureSaveBridge.currentOrNull());
            try (var scope = FullRegistrationFixtureSaveBridge.enter(binding)) {
                assertSame(binding, FullRegistrationFixtureSaveBridge.current(fixture.world.level));
                assertThrows(IllegalStateException.class,
                        () -> FullRegistrationFixtureSaveBridge.current(foreignLevel));
            }
            assertNull(FullRegistrationFixtureSaveBridge.currentOrNull());
            var callbackFailure = new IllegalStateException("original controlled callback failed");
            assertSame(callbackFailure, assertThrows(IllegalStateException.class, () -> {
                try (var scope = FullRegistrationFixtureSaveBridge.enter(binding)) {
                    assertSame(binding, FullRegistrationFixtureSaveBridge.currentOrNull());
                    throw callbackFailure;
                }
            }));
            assertNull(FullRegistrationFixtureSaveBridge.currentOrNull(),
                    "a throwing original callback must still clear the synchronous substitution");
        } finally { fixture.stop(); }
    }

    @Test
    void fixtureStopPermanentlyRejectsItsRetainedBinding() throws Exception {
        var fixture = new FullRegistrationFixture(true);
        var binding = FullRegistrationFixtureSaveBridge.find(fixture.world.level, fixture.fixtureMap);
        assertNotNull(binding);
        var finishing = FullFinishingServices.forServer(fixture.world.level.getServer());
        binding.finishing(finishing);
        var releases = new AtomicInteger();
        var attempt = fixture.saveCoordinator.begin(new RegionalSaveCoordinator.Source() {
            public RoutingKey key() { return binding.key(ChunkPos.ZERO); }
            public boolean current() { return true; }
            public void restoreDirty() { fail("clean save acknowledgement restored dirty state"); }
            public void release() { releases.incrementAndGet(); }
        });
        assertFalse(attempt.markSaved(() -> false));
        attempt.finishBody();
        assertEquals(1, fixture.saveCoordinator.pending());
        fixture.stop();
        assertEquals(0, fixture.saveCoordinator.pending());
        assertEquals(1, releases.get(), "fixture teardown must settle genuine save custody");
        assertNull(FullRegistrationFixtureSaveBridge.find(fixture.world.level, fixture.fixtureMap));
        assertThrows(IllegalStateException.class, () -> FullRegistrationFixtureSaveBridge.enter(binding));
        assertNull(FullRegistrationFixtureSaveBridge.currentOrNull());
        assertThrows(ChunkProtocolException.class,
                () -> FullFinishingServices.forServer(fixture.world.level.getServer()),
                "fixture teardown must retire its synthetic server service identity");
    }

    @Test
    void controlledUnloadBoundaryAcknowledgesOnlyPublishedCompletionAndKeepsOriginalFailurePrimary() throws Exception {
        var fixture = new FullRegistrationFixture(true);
        try {
            var binding = FullRegistrationFixtureSaveBridge.find(fixture.world.level, fixture.fixtureMap);
            var releases = new AtomicInteger();
            var attempt = fixture.saveCoordinator.begin(new RegionalSaveCoordinator.Source() {
                public RoutingKey key() { return binding.key(ChunkPos.ZERO); }
                public boolean current() { return true; }
                public void restoreDirty() { fail("clean attempt restored dirty state"); }
                public void release() { releases.incrementAndGet(); }
            });
            assertFalse(attempt.markSaved(() -> false));
            attempt.finishBody();
            assertEquals(1, fixture.saveCoordinator.pending());
            FullRegistrationFixtureSaveBridge.acknowledgeCompleted(binding, null);
            assertEquals(0, fixture.saveCoordinator.pending());
            assertEquals(1, releases.get());

            var original = new IllegalStateException("unchanged unload body failed");
            var acknowledgement = new IllegalArgumentException("owner acknowledgement failed");
            var failed = fixture.saveCoordinator.begin(new RegionalSaveCoordinator.Source() {
                public RoutingKey key() { return binding.key(ChunkPos.ZERO); }
                public boolean current() { return true; }
                public void restoreDirty() { }
                public void release() { throw acknowledgement; }
            });
            assertFalse(failed.markSaved(() -> false));
            failed.finishBody();
            FullRegistrationFixtureSaveBridge.acknowledgeCompleted(binding, original);
            assertArrayEquals(new Throwable[]{acknowledgement}, original.getSuppressed());
            assertEquals(0, fixture.saveCoordinator.pending());
        } finally { fixture.stop(); }
    }
}
