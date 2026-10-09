package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RegionalServiceDispatcherTest {
    @Test
    void realEnclosurePublicationIsProgressBeforeTheFollowingBoundaryAdvance() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var routes = new LinkedHashMap<>(f.directory.snapshot().routes());
        var generation = f.directory.snapshot().generation().next();
        routes.replaceAll((key, route) -> new RegionRoute(route.region(), generation, route.ownerEpoch()));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            var owner = x == 1 ? f.b : f.a;
            routes.put(new RoutingKey(f.world, x, z), new RegionRoute(owner.id(), generation, owner.ownerEpoch()));
        }
        f.directory.publish(generation, routes);
        var enclosure = new dev.phoenium.chunk.FullRegionalEnclosure(f.directory, f.runtimes, f.gateway, null);
        dispatcher.installClosureEncloser(enclosure::enclose);
        dispatcher.installBoundaryAdvancer(enclosure::advance);

        dispatcher.ensureClosure(f.aKey, 1);

        assertEquals(generation.next(), f.directory.snapshot().generation());
        assertEquals(routes.keySet(), f.directory.snapshot().routes().keySet());
        assertTrue(f.directory.snapshot().routes().values().stream().allMatch(route -> route.region().equals(f.aId)));
        var executed = new java.util.ArrayList<RegionId>();
        dispatcher.executeAndDrain(f.aKey, RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK,
            execution -> { dispatcher.requireCurrentClosure(f.aKey, 1); executed.add(execution.runtime().id()); });
        assertEquals(List.of(f.aId), executed);
        assertFalse(f.barrier.tripped());
        assertFalse(f.a.leases().hasActiveLease());
        assertFalse(f.b.leases().hasActiveLease());
    }

    @Test
    void earlierOwnerFailureIsPreservedWhenAnotherServiceIsWaitingToExecute() {
        Fixture f = new Fixture(); var dispatcher = f.dispatcher();
        var first = new IllegalStateException("earlier original callback");
        dispatcher.submit(f.aKey, RegionalServiceKind.CHUNK_GAMEPLAY, execution -> { throw first; });
        var waiting = dispatcher.submit(f.bKey, RegionalServiceKind.TRACKING_UPDATE,
            execution -> fail("later callback must remain fenced"));
        var failure = assertThrows(IllegalStateException.class, () -> dispatcher.drain(waiting));
        assertSame(first, failure.getCause());
        assertSame(first, f.barrier.record().orElseThrow().cause());
        assertFalse(waiting.completed());
        assertFalse(f.a.leases().hasActiveLease());
        assertFalse(f.b.leases().hasActiveLease());
    }

    @Test
    void nativeSelectionIsTakenAfterPreviouslyAcceptedOwnerEffects() {
        Fixture f = new Fixture(); var dispatcher = f.dispatcher();
        var state = new java.util.ArrayList<String>();
        dispatcher.submit(f.aKey, RegionalServiceKind.TRACKING_UPDATE, execution -> state.add("moved"));
        dispatcher.drainBeforeSelection();
        var selected = List.copyOf(state);
        dispatcher.executeAndDrain(f.bKey, RegionalServiceKind.TRACKING_UPDATE,
            execution -> assertEquals(selected, state));
        assertEquals(List.of("moved"), selected);
        assertEquals(0, dispatcher.pendingCount());
        assertFalse(f.barrier.tripped());
    }

    @Test
    void initialTrackingPairingCapturesAfterQueuedMovementSettles() {
        Fixture f = new Fixture(); var dispatcher = f.dispatcher();
        var published = new java.util.concurrent.atomic.AtomicReference<>(f.aKey);
        var paired = new java.util.concurrent.atomic.AtomicInteger();
        dispatcher.submit(f.aKey, RegionalServiceKind.TRACKING_UPDATE,
            execution -> published.set(f.bKey));

        // addEntity must take its player receipt after this owner-free selection boundary.
        dispatcher.drainBeforeSelection();
        RoutingKey captured = published.get();
        assertEquals(f.bKey, captured);
        dispatcher.executeAndDrain(captured, RegionalServiceKind.TRACKING_PAIR, execution -> {
            assertEquals(f.bId, execution.runtime().id());
            assertEquals(captured, published.get());
            paired.incrementAndGet();
        });

        assertEquals(1, paired.get());
        assertEquals(0, dispatcher.pendingCount());
        assertFalse(f.barrier.tripped());
    }

    @Test
    void unresolvedClosureStillFailsWithoutRunningTheCallbackOrPublishingRoutes() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var enclosure = new dev.phoenium.chunk.FullRegionalEnclosure(f.directory, f.runtimes, f.gateway, null);
        dispatcher.installClosureEncloser(enclosure::enclose);
        dispatcher.installBoundaryAdvancer(enclosure::advance);
        var before = f.directory.snapshot();
        var failure = assertThrows(IllegalStateException.class, () -> dispatcher.ensureClosure(f.aKey, 1));
        assertTrue(failure.getMessage().contains("cannot progress"));
        assertSame(before, f.directory.snapshot());
        assertEquals(0, dispatcher.pendingCount());
    }

    @Test
    void exactTypedBodyExecutesOnlyUnderCurrentOwnerLease() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var observed = new java.util.ArrayList<String>();
        var handle = dispatcher.submit(f.aKey, RegionalServiceKind.SAVE_CAPTURE, execution -> {
            assertEquals(f.aId, execution.runtime().id());
            assertEquals(RegionalServiceKind.SAVE_CAPTURE, execution.kind());
            assertTrue(OwnershipContext.current().isPresent());
            execution.runtime().append("capture");
            observed.add(execution.serviceId() + ":" + execution.worldInstance());
        });

        assertTrue(observed.isEmpty());
        assertEquals(1, dispatcher.pendingCount());
        dispatcher.drain(handle);

        assertEquals(List.of("capture"), f.a.immutableLog());
        assertEquals(List.of("stage4-test:7"), observed);
        assertEquals(0, dispatcher.pendingCount());
        assertFalse(f.a.leases().hasActiveLease());
    }

    @Test
    void c2mePersistedPoiHandleCompletesOnlyAfterItsOwnerLeaseIsReleased() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var reference = new java.util.concurrent.atomic.AtomicReference<RegionalServiceDispatcher.Handle>();
        var handle = dispatcher.submitC2mePersistedPoiAfterRelease(f.aKey, execution -> {
            assertTrue(OwnershipContext.current().isPresent());
            assertTrue(f.a.leases().hasActiveLease());
            assertFalse(reference.get().completed());
            assertFalse(reference.get().completion().toCompletableFuture().isDone());
            f.a.append("c2me-poi");
        });
        reference.set(handle);
        var completionReleased = new java.util.concurrent.atomic.AtomicBoolean();
        handle.completion().whenComplete((ignored, failure) -> {
            assertNull(failure);
            assertTrue(OwnershipContext.current().isEmpty(),
                    "after-release continuation must not inherit owner authority");
            assertFalse(f.a.leases().hasActiveLease(),
                    "after-release continuation must observe the regional lease as released");
            completionReleased.set(true);
        });

        assertTrue(dispatcher.hasPending(RegionalServiceKind.C2ME_PERSISTED_POI_RECONCILE));
        assertFalse(handle.completion().toCompletableFuture().isDone());
        f.scheduler.advanceOne();

        assertEquals(List.of("c2me-poi"), f.a.immutableLog());
        assertFalse(f.a.leases().hasActiveLease());
        assertTrue(handle.completed());
        assertTrue(handle.completion().toCompletableFuture().isDone());
        assertTrue(completionReleased.get());
        assertFalse(dispatcher.hasPending(RegionalServiceKind.C2ME_PERSISTED_POI_RECONCILE));
        assertFalse(f.barrier.tripped());
    }

    @Test
    void staleQueuedServiceReroutesAtDeliveryWithoutReplayingBody() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var owners = new java.util.ArrayList<RegionId>();
        var handle = dispatcher.submit(f.aKey, RegionalServiceKind.TRACKING_UPDATE,
            execution -> owners.add(execution.runtime().id()));

        TopologyGeneration next = f.directory.snapshot().generation().next();
        f.directory.publish(next, Map.of(
            f.aKey, new RegionRoute(f.bId, next, f.b.ownerEpoch()),
            f.bKey, new RegionRoute(f.bId, next, f.b.ownerEpoch())));
        dispatcher.drain(handle);
        assertEquals(List.of(f.bId), owners);
        assertEquals(0, dispatcher.pendingCount());
    }

    @Test
    void directExecutorCallCannotBypassLeaseAndOwnerContext() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var handle = dispatcher.submit(f.aKey, RegionalServiceKind.SAVE_CAPTURE, execution -> { });
        var route = f.directory.resolve(f.aKey).orElseThrow();
        var payload = new dev.phoenium.task.TaskPayload.RegionalService(dispatcher.serviceId(), dispatcher.worldInstance(),
            handle.operationId(), handle.kind().name());
        assertThrows(IllegalStateException.class, () -> dispatcher.execute(f.a, payload));
        assertEquals(1, dispatcher.abandonPending());
    }

    @Test
    void ownerHeldDrainIsRejectedBeforeAnotherLeaseCanBeAcquired() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var second = dispatcher.submit(f.bKey, RegionalServiceKind.BLOCK_EVENT, execution -> { });
        var first = dispatcher.submit(f.aKey, RegionalServiceKind.BLOCK_EVENT,
            execution -> assertThrows(IllegalStateException.class, () -> dispatcher.drain(second)));

        dispatcher.drain(first);
        dispatcher.drain(second);
        assertFalse(f.a.leases().hasActiveLease());
        assertFalse(f.b.leases().hasActiveLease());
    }

    @Test
    void callbackFailureTripsCommonBarrierAndRetainedBodyIsReleasedByDrain() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var handle = dispatcher.submit(f.aKey, RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK,
            execution -> { throw new IllegalArgumentException("tick callback failed"); });

        var failure = assertThrows(IllegalArgumentException.class,
            () -> dispatcher.drain(handle));
        assertEquals("tick callback failed", failure.getMessage());
        assertTrue(f.barrier.tripped());
        assertEquals(0, dispatcher.pendingCount());
    }

    /** S9-D10: with every inbox empty, executeAndDrain's turn is the scheduler's own leased turn, not queued. */
    @Test
    void directTurnRunsUnderItsOwnerLeaseWithoutTheInbox() {
        Fixture f = new Fixture(); var dispatcher = f.dispatcher();
        long attempts = f.scheduler.deliveryAttempts(), advances = f.scheduler.advances();
        var seen = new java.util.ArrayList<Object>();
        dispatcher.executeAndDrain(f.aKey, RegionalServiceKind.TRACKING_UPDATE, execution -> {
            seen.add(OwnershipContext.current().orElseThrow().region());
            seen.add(f.a.leases().hasActiveLease());
            seen.add(f.a.lifecycle());
            seen.add(f.a.inboxEmpty() && f.b.inboxEmpty());
            seen.add(dispatcher.pendingCount());
        });
        assertEquals(List.of(f.aId, true, RegionLifecycle.TICKING, true, 1), seen);
        assertEquals(attempts + 1, f.scheduler.deliveryAttempts());
        assertEquals(advances + 1, f.scheduler.advances());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(RegionLifecycle.READY, f.a.lifecycle());
        assertFalse(f.a.leases().hasActiveLease());
        assertTrue(OwnershipContext.current().isEmpty());
    }

    /** S9-D10: queued work anywhere keeps the queued path, so the drain still selects in region-id order: work queued
     * on region a runs before a drained turn on region b. */
    @Test
    void earlierQueuedWorkStillRunsBeforeTheDrainedOperation() {
        Fixture f = new Fixture(); var dispatcher = f.dispatcher();
        var order = new java.util.ArrayList<String>();
        var earlier = dispatcher.submit(f.aKey, RegionalServiceKind.BLOCK_EVENT, execution -> order.add("earlier"));
        dispatcher.executeAndDrain(f.bKey, RegionalServiceKind.BLOCK_EVENT, execution -> order.add("drained"));
        assertEquals(List.of("earlier", "drained"), order);
        assertTrue(earlier.completed());
        assertEquals(0, dispatcher.pendingCount());
    }

    /** S9-D10: a direct turn's failure trips the barrier and is thrown exactly as the drain loop throws it. */
    @Test
    void directTurnFailureIsReportedAsTheDrainReportsIt() {
        Fixture f = new Fixture(); var dispatcher = f.dispatcher();
        var failure = assertThrows(IllegalArgumentException.class, () -> dispatcher.executeAndDrain(f.aKey,
            RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK, execution -> { throw new IllegalArgumentException("direct"); }));
        assertEquals("direct", failure.getMessage());
        assertTrue(f.barrier.tripped());
        assertEquals(0, dispatcher.pendingCount());
        assertFalse(f.a.leases().hasActiveLease());
        assertThrows(IllegalStateException.class, () -> dispatcher.executeAndDrain(f.bKey,
            RegionalServiceKind.TRACKING_UPDATE, execution -> fail("no turn after the barrier tripped")));
    }

    /** S9-D10: an owner caller is refused as before, and its own turn is unaffected. */
    @Test
    void ownerCallerCannotTakeADirectTurn() {
        Fixture f = new Fixture(); var dispatcher = f.dispatcher();
        var refused = new java.util.ArrayList<Boolean>();
        dispatcher.executeAndDrain(f.aKey, RegionalServiceKind.BLOCK_EVENT, execution -> refused.add(
            assertThrows(IllegalStateException.class, () -> dispatcher.executeAndDrain(f.bKey,
                RegionalServiceKind.BLOCK_EVENT, inner -> fail("nested owner turn"))) != null));
        assertEquals(List.of(true), refused);
        assertFalse(f.barrier.tripped());
        assertFalse(f.a.leases().hasActiveLease());
        assertFalse(f.b.leases().hasActiveLease());
    }

    /** S9-D11: a uniformly routed world with an idle boundary runs consecutive items in one owner turn. */
    @Test
    void executeEachSharesOneTurnInAUniformWorld() {
        Uniform u = new Uniform(); u.dispatcher.installBoundaryIdle(() -> true);
        long advances = u.scheduler.advances();
        var seen = new java.util.ArrayList<String>();
        var prepared = new java.util.ArrayList<String>();
        u.dispatcher.executeEach(u.world, 4, i -> {
            prepared.add(i + (OwnershipContext.current().isPresent() ? ":owned" : ":free"));
            return new RoutingKey(u.world, i * 3, -i);
        }, RegionalServiceKind.TRACKING_UPDATE,
            i -> seen.add(i + "@" + OwnershipContext.current().orElseThrow().region().value()));
        assertEquals(List.of("0:free", "1:owned", "2:owned", "3:owned"), prepared);
        assertEquals(List.of("0@u", "1@u", "2@u", "3@u"), seen);
        assertEquals(advances + 1, u.scheduler.advances());
        assertEquals(0, u.dispatcher.pendingCount());
        assertFalse(u.runtime.leases().hasActiveLease());
    }

    /** S9-D11: work queued by an item ends the shared turn, so it runs before the next item. */
    @Test
    void executeEachLetsQueuedWorkRunBeforeTheNextItem() {
        Uniform u = new Uniform(); u.dispatcher.installBoundaryIdle(() -> true);
        var order = new java.util.ArrayList<String>();
        u.dispatcher.executeEach(u.world, 4, i -> new RoutingKey(u.world, i, 0), RegionalServiceKind.TRACKING_UPDATE, i -> {
            order.add("item" + i);
            if (i == 1) u.dispatcher.submit(new RoutingKey(u.world, 9, 9), RegionalServiceKind.BLOCK_EVENT,
                execution -> order.add("queued"));
        });
        assertEquals(List.of("item0", "item1", "queued", "item2", "item3"), order);
        assertEquals(0, u.dispatcher.pendingCount());
    }

    /** S9-D11: without an idle boundary, or in an explicitly routed world, every item takes its own turn. */
    @Test
    void executeEachTakesOneTurnPerItemUnlessUniformAndIdle() {
        Uniform u = new Uniform();
        long advances = u.scheduler.advances();
        u.dispatcher.executeEach(u.world, 3, i -> new RoutingKey(u.world, i, 0), RegionalServiceKind.TRACKING_UPDATE, i -> { });
        assertEquals(advances + 3, u.scheduler.advances());

        Fixture f = new Fixture(); var dispatcher = f.dispatcher(); dispatcher.installBoundaryIdle(() -> true);
        long explicit = f.scheduler.advances();
        var regions = new java.util.ArrayList<RegionId>();
        dispatcher.executeEach(f.world, 2, i -> i == 0 ? f.aKey : f.bKey, RegionalServiceKind.TRACKING_UPDATE,
            i -> regions.add(OwnershipContext.current().orElseThrow().region()));
        assertEquals(List.of(f.aId, f.bId), regions);
        assertEquals(explicit + 2, f.scheduler.advances());
        assertThrows(IllegalArgumentException.class, () -> dispatcher.executeEach(f.world, 1, i -> f.aKey,
            RegionalServiceKind.BLOCK_EVENT, i -> { }));
    }

    /** S9-D11: a failing item trips the barrier and ends the sequence there. */
    @Test
    void executeEachFailureEndsTheSequenceAtItsItem() {
        Uniform u = new Uniform(); u.dispatcher.installBoundaryIdle(() -> true);
        var ran = new java.util.ArrayList<Integer>();
        var failure = assertThrows(IllegalStateException.class, () -> u.dispatcher.executeEach(u.world, 4,
            i -> new RoutingKey(u.world, i, 0), RegionalServiceKind.TRACKING_UPDATE, i -> {
                ran.add(i);
                if (i == 2) throw new IllegalStateException("item 2");
            }));
        assertEquals("item 2", failure.getMessage());
        assertEquals(List.of(0, 1, 2), ran);
        assertTrue(u.barrier.tripped());
        assertFalse(u.runtime.leases().hasActiveLease());
    }

    /** One world routed entirely by its default route to one region. */
    private static final class Uniform {
        final WorldId world = new WorldId("uniform");
        final RegionId id = new RegionId(world, "u");
        final RegionRuntime runtime = Fixture.ready(id);
        final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>(Map.of(id, runtime));
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final RegionScheduler scheduler;
        final RegionalServiceDispatcher dispatcher;
        Uniform() {
            directory.publish(new TopologyGeneration(1), Map.of(),
                Map.of(world, new RegionRoute(id, new TopologyGeneration(1), runtime.ownerEpoch())));
            scheduler = new RegionScheduler(runtimes, directory, barrier);
            var gateway = new OwnerGateway(directory, runtimes, scheduler);
            dispatcher = new RegionalServiceDispatcher("stage9-uniform", 9, directory, runtimes, gateway, scheduler);
        }
    }

    private static final class Fixture {
        final WorldId world = new WorldId("test");
        final RegionId aId = new RegionId(world, "a");
        final RegionId bId = new RegionId(world, "b");
        final RoutingKey aKey = new RoutingKey(world, 0, 0);
        final RoutingKey bKey = new RoutingKey(world, 10, 0);
        final RegionRuntime a = ready(aId);
        final RegionRuntime b = ready(bId);
        final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>(Map.of(aId, a, bId, b));
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final RegionScheduler scheduler;
        final OwnerGateway gateway;
        Fixture() {
            directory.publish(new TopologyGeneration(1), Map.of(
                aKey, new RegionRoute(aId, new TopologyGeneration(1), a.ownerEpoch()),
                bKey, new RegionRoute(bId, new TopologyGeneration(1), b.ownerEpoch())));
            scheduler = new RegionScheduler(runtimes, directory, barrier);
            gateway = new OwnerGateway(directory, runtimes, scheduler);
        }
        RegionalServiceDispatcher dispatcher() {
            return new RegionalServiceDispatcher("stage4-test", 7, directory, runtimes, gateway, scheduler);
        }
        static RegionRuntime ready(RegionId id) {
            RegionRuntime runtime = new RegionRuntime(id, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            return runtime;
        }
    }
}
