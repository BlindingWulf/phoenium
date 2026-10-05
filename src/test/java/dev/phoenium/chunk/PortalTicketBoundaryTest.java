package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.live.PortalTicketBoundary;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary-level contract for owner-correct retention of a foreign-owned native portal ticket. */
class PortalTicketBoundaryTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }

    /** Source chunk (6,0) belongs to A (stage2-live-world); (10,10) belongs to B (zz-exit). */
    private static final class Scene {
        final NativeItemPortalTransferTest.Fixture f;
        final RoutingKey sourceKey, farKey, ownKey;
        final RegionId a, b;
        final Pig pig;
        final List<String> replays = new ArrayList<>();
        final PortalTicketBoundary boundary;
        java.util.function.Supplier<RuntimeException> replayFailure;

        Scene() throws Exception {
            f = new NativeItemPortalTransferTest.Fixture(net.minecraft.world.level.Level.END);
            a = f.br;
            b = new RegionId(f.b, "zz-exit");
            var runtime = new RegionRuntime(b, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            f.runtimes.put(b, runtime);
            sourceKey = f.to;
            ownKey = new RoutingKey(f.b, 7, 0);
            farKey = new RoutingKey(f.b, 10, 10);
            publish(Map.of(sourceKey, a, ownKey, a, farKey, b));
            NativePortalDestinationPreparationTest.installNativeLookup(f);
            FullDecisionWitnessSupport.set(f.nether, net.minecraft.world.level.Level.class, "registryAccess",
                NativePortalDestinationPreparationTest.dynamicRegistries());
            FullDecisionWitnessSupport.set(f.nether, net.minecraft.server.level.ServerLevel.class,
                "environmentAttributes", net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build());
            pig = EntityTypes.PIG.create(f.nether, EntitySpawnReason.COMMAND);
            assertNotNull(pig);
            pig.setPos(96.5, 65.0, 8.5);
            f.publish(pig, sourceKey);
            boundary = new PortalTicketBoundary(f.authority,
                key -> f.directory.resolve(key).map(RegionRoute::region),
                (entity, position) -> {
                    replays.add(OwnershipContext.current().map(c -> c.region().value()).orElse("NONE")
                        + "@" + position.toShortString());
                    if (replayFailure != null) throw replayFailure.get();
                });
        }

        void publish(Map<RoutingKey, RegionId> owners) {
            var generation = f.directory.snapshot().generation().next();
            var routes = new java.util.LinkedHashMap<RoutingKey, RegionRoute>();
            routes.put(f.from, new RegionRoute(f.ar, generation, new OwnerEpoch(0)));
            owners.forEach((key, owner) -> routes.put(key, new RegionRoute(owner, generation, new OwnerEpoch(0))));
            f.directory.publish(generation, routes);
        }

        boolean ownerTurnPlace(BlockPos position) {
            boolean[] deferred = new boolean[1];
            f.dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.ENTITY_PORTAL_SOURCE,
                ignored -> deferred[0] = boundary.place(pig, position));
            return deferred[0];
        }
    }

    @Test void ownerFreeCallerIsNeverRetained() throws Exception {
        var s = new Scene();
        assertFalse(s.boundary.place(s.pig, new BlockPos(160, 65, 160)));
        assertEquals(0, s.boundary.pendingCount());
    }

    @Test void ticketForTheCallersOwnChunkStaysNativeInline() throws Exception {
        var s = new Scene();
        assertFalse(s.ownerTurnPlace(new BlockPos(112, 65, 8)), "chunk (7,0) is A's own chunk");
        assertEquals(0, s.boundary.pendingCount());
        assertEquals(0, s.boundary.deferredCount());
    }

    @Test void unroutedChunkKeepsTheNativeInlineBehaviour() throws Exception {
        var s = new Scene();
        assertFalse(s.ownerTurnPlace(new BlockPos(16 * 40, 65, 16 * 40)));
        assertEquals(0, s.boundary.pendingCount());
    }

    @Test void foreignChunkIsRetainedThenPlacedOnceUnderItsOwner() throws Exception {
        var s = new Scene();
        BlockPos position = new BlockPos(160, 65, 160);
        assertTrue(s.ownerTurnPlace(position));
        assertEquals(1, s.boundary.pendingCount());
        assertEquals(List.of(), s.replays, "nothing may run inside the producing owner turn");
        assertEquals(1, s.boundary.advance());
        assertEquals(List.of("zz-exit@160, 65, 160"), s.replays);
        assertEquals(0, s.boundary.pendingCount());
        assertEquals(0, s.boundary.advance(), "a settled request is never replayed");
        assertEquals(List.of("zz-exit@160, 65, 160"), s.replays);
        assertEquals(1, s.boundary.placedCount());
        assertEquals(0, s.boundary.droppedCount());
    }

    @Test void requestIsReplayedUnderTheOwnerPublishedAtReplayTime() throws Exception {
        var s = new Scene();
        assertTrue(s.ownerTurnPlace(new BlockPos(160, 65, 160)));
        var third = new RegionId(s.f.b, "zz-third");
        var runtime = new RegionRuntime(third, new OwnerEpoch(0));
        runtime.transitionTo(RegionLifecycle.READY);
        s.f.runtimes.put(third, runtime);
        s.publish(Map.of(s.sourceKey, s.a, s.ownKey, s.a, s.farKey, third));
        assertEquals(1, s.boundary.advance());
        assertEquals(List.of("zz-third@160, 65, 160"), s.replays, "a stale owner must not receive the effect");
    }

    @Test void lostRouteDropsTheRequestInsteadOfGuessingAnOwner() throws Exception {
        var s = new Scene();
        assertTrue(s.ownerTurnPlace(new BlockPos(160, 65, 160)));
        s.publish(Map.of(s.sourceKey, s.a, s.ownKey, s.a));
        assertEquals(0, s.boundary.advance());
        assertEquals(List.of(), s.replays);
        assertEquals(1, s.boundary.droppedCount());
        assertEquals(0, s.boundary.pendingCount());
    }

    @Test void inactiveWorldDropsTheRequest() throws Exception {
        var s = new Scene();
        assertTrue(s.ownerTurnPlace(new BlockPos(160, 65, 160)));
        s.f.running = false;
        assertEquals(0, s.boundary.advance());
        assertEquals(List.of(), s.replays);
        assertEquals(1, s.boundary.droppedCount());
    }

    @Test void worldClosingAndShutdownDiscardPendingDemand() throws Exception {
        var s = new Scene();
        assertTrue(s.ownerTurnPlace(new BlockPos(160, 65, 160)));
        s.boundary.worldClosing(s.f.nether);
        assertEquals(0, s.boundary.pendingCount());
        assertEquals(1, s.boundary.droppedCount());
        assertTrue(s.ownerTurnPlace(new BlockPos(161, 65, 160)));
        s.boundary.close();
        assertEquals(0, s.boundary.pendingCount());
        assertEquals(2, s.boundary.droppedCount());
        assertFalse(s.ownerTurnPlace(new BlockPos(160, 65, 160)), "a closed boundary never retains");
        assertEquals(0, s.boundary.advance());
        assertEquals(List.of(), s.replays);
    }

    @Test void advanceRejectsRetainedOwnerAuthority() throws Exception {
        var s = new Scene();
        s.f.dispatcher.executeAndDrain(s.sourceKey, RegionalServiceKind.ENTITY_PORTAL_SOURCE,
            ignored -> assertThrows(IllegalStateException.class, s.boundary::advance));
    }

    @Test void replayFailurePropagatesAfterTheRemainingRequestsRan() throws Exception {
        var s = new Scene();
        assertTrue(s.ownerTurnPlace(new BlockPos(160, 65, 160)));
        assertTrue(s.ownerTurnPlace(new BlockPos(162, 65, 160)));
        var failures = new ArrayList<RuntimeException>();
        s.replayFailure = () -> {
            var failure = new IllegalStateException("controlled replay failure " + failures.size());
            failures.add(failure); return failure;
        };
        var thrown = assertThrows(IllegalStateException.class, s.boundary::advance);
        assertSame(failures.getFirst(), thrown, "the first failure is the one reported");
        assertEquals(2, s.replays.size(), "one failed replay must not strand the other request");
        assertEquals(List.of(failures.get(1)), List.of(thrown.getSuppressed()));
        assertEquals(2, s.boundary.droppedCount());
        assertEquals(0, s.boundary.pendingCount());
        assertEquals(0, s.boundary.advance(), "the failed request is not retried");
    }

    @Test void retainedRequestsReplayInProductionOrder() throws Exception {
        var s = new Scene();
        for (int i = 0; i < 5; i++) assertTrue(s.ownerTurnPlace(new BlockPos(160 + i, 65, 160)));
        assertEquals(5, s.boundary.advance());
        assertEquals(List.of("zz-exit@160, 65, 160", "zz-exit@161, 65, 160", "zz-exit@162, 65, 160",
            "zz-exit@163, 65, 160", "zz-exit@164, 65, 160"), s.replays);
    }

    @Test void backlogIsBoundedAndFailsLoudly() throws Exception {
        var s = new Scene();
        s.f.dispatcher.executeAndDrain(s.sourceKey, RegionalServiceKind.ENTITY_PORTAL_SOURCE, ignored -> {
            for (int i = 0; i < 4096; i++) assertTrue(s.boundary.place(s.pig, new BlockPos(160, 65, 160)));
            assertThrows(IllegalStateException.class, () -> s.boundary.place(s.pig, new BlockPos(160, 65, 160)));
        });
        assertEquals(4096, s.boundary.pendingCount());
        s.boundary.close();
    }
}
