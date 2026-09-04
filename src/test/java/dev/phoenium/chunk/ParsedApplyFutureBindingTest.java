package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FUT-01 through FUT-18: the finite Decision-A graph and actual holder evidence. */
class ParsedApplyFutureBindingTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void fut01SuccessIdentity() {
        Fixture fresh = new Fixture(false);
        fresh.succeed();
        assertSame(fresh.value, fresh.graph.parsedInner().join());
        assertSame(fresh.value, fresh.returned.join());
        assertSame(fresh.value, fresh.holderFuture.join().orElseThrow(AssertionError::new));

        Fixture reload = new Fixture(true);
        reload.lightInputs = List.of(new LightInputService.SectionInput(LightInputService.Layer.BLOCK, "s0", 1));
        reload.succeed();
        assertSame(reload.value, reload.returned.join());
        assertSame(reload.value, reload.holderFuture.join().orElseThrow(AssertionError::new));
        assertEquals(1, reload.applyCalls);
    }

    @Test void fut02MapperGenuineFailure() {
        Throwable root = new IllegalStateException("plain");
        List<Throwable> failures = List.of(root, new AssertionError("error"),
                new CompletionException(root), new CompletionException(new CompletionException(root)));
        for (Throwable failure : failures) {
            Fixture f = new Fixture(false); f.applyFailure = failure;
            f.succeed();
            assertEquals(1, f.applyCalls); assertEquals(1, f.recoveryCalls);
            assertSame(failure instanceof CompletionException ? failure : failure,
                    deepestOrSelf(f.recoveryInput, failure));
            assertFalse(f.barrier.tripped()); assertEquals(0, f.terminals.size());
        }
    }

    @Test void fut03UpstreamFailure() {
        RuntimeException read = new RuntimeException("read");
        Fixture a = new Fixture(false); a.completePoi(); a.parsedSource.completeExceptionally(read); a.drainAll();
        assertEquals(0, a.applyCalls); assertEquals(1, a.recoveryCalls); assertSame(read, rootCause(a.recoveryInput));

        RuntimeException poi = new RuntimeException("poi");
        Fixture b = new Fixture(false); b.parsedSource.complete(Optional.empty()); b.poiSource.completeExceptionally(poi); b.drainAll();
        assertEquals(0, b.applyCalls); assertEquals(1, b.recoveryCalls); assertSame(poi, rootCause(b.recoveryInput));
    }

    @Test void fut04StalePending() {
        Fixture f = new Fixture(false); f.completePoi(); f.parsedSource.complete(Optional.empty());
        Item original = f.inbox.peek(); assertNotNull(original); assertEquals(f.graph.parsedStage(), original.stage().stage());
        f.move(f.b, 2);
        assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, f.executeOne(false));
        assertFalse(f.graph.parsedInner().isDone()); assertFalse(f.returned.isDone()); assertFalse(f.holderFuture.isDone());
        assertEquals(0, f.applyCalls); assertEquals(0, f.recoveryCalls); assertEquals(0, f.publicationCalls);
        assertEquals(ChunkCustodyModel.StageState.QUEUED, f.graph.parsedStage().equals(original.stage().stage())
                ? original.stage().state().orElseThrow() : null);
    }

    @Test void fut05StaleThenSuccess() {
        Fixture crossOwner = new Fixture(false); crossOwner.completePoi(); crossOwner.parsedSource.complete(Optional.empty());
        crossOwner.move(crossOwner.b, 2); assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, crossOwner.executeOne(true));
        crossOwner.drainAll(); assertSame(crossOwner.value, crossOwner.returned.join()); assertEquals(1, crossOwner.applyCalls);
        assertEquals("FUT-B", crossOwner.applyOwner.value());

        Fixture generationRefresh = new Fixture(false); generationRefresh.completePoi(); generationRefresh.parsedSource.complete(Optional.empty());
        generationRefresh.move(generationRefresh.a, 2); assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, generationRefresh.executeOne(true));
        generationRefresh.drainAll(); assertEquals(1, generationRefresh.applyCalls); assertSame(generationRefresh.value, generationRefresh.returned.join());
    }

    @Test void fut06SupersededLifecycle() throws Exception {
        Fixture f = new Fixture(false); f.installOldStatusSlot(); f.completePoi(); f.parsedSource.complete(Optional.empty());
        ChunkHolder replacement = actualHolder(f.position);
        var next = f.services.lifecycles().activateHolder(1, f.key, replacement);
        assertNotEquals(f.lifecycle, next);
        assertEquals(ChunkStageAdmission.Result.SUPERSEDED, f.executeOne(true));
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, f.holderFuture.join());
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, f.oldStatus.join());
        assertNull(f.statusSlots().get(ChunkStatus.EMPTY.getIndex()));
        assertEquals(0, f.applyCalls); assertEquals(0, f.recoveryCalls); assertTrue(f.services.lifecycles().isCurrent(next));
    }

    @Test void fut07Shutdown() {
        Fixture beforeCallback = new Fixture(false); beforeCallback.graph.shutdown();
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, beforeCallback.holderFuture.join());
        assertEquals(0, beforeCallback.applyCalls);

        Fixture ready = new Fixture(false); ready.completePoi(); ready.parsedSource.complete(Optional.empty()); ready.graph.shutdown();
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, ready.holderFuture.join()); assertEquals(0, ready.applyCalls);

        Fixture beforeArm = new Fixture(false, Runnable::run, false, false);
        beforeArm.graph.shutdown(); beforeArm.attach();
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, beforeArm.holderFuture.join());

        Executor reject = ignored -> { throw new RejectedExecutionException("stopped"); };
        Fixture stoppedExecutor = new Fixture(false, reject, false, false);
        stoppedExecutor.poiSource.complete(Optional.empty()); stoppedExecutor.attach();
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, stoppedExecutor.holderFuture.join());
        assertEquals(0, stoppedExecutor.applyCalls); assertTrue(stoppedExecutor.callbackBarriers > 0);
    }

    @Test void fut08CallbackOnce() {
        Fixture f = new Fixture(false); f.completePoi(); f.parsedSource.complete(Optional.empty());
        Item delivery = f.inbox.peek(); assertNotNull(delivery); f.inbox.addLast(delivery);
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.executeOne(false));
        assertEquals(ChunkStageAdmission.Result.DUPLICATE, f.executeOne(false));
        f.drainAll(); assertEquals(1, f.applyCalls); assertEquals(0, f.recoveryCalls); assertFalse(f.barrier.tripped());
    }

    @Test void fut09CompletedUpstreamRace() {
        Fixture inline = new Fixture(true, Runnable::run, false, true);
        assertTrue(inline.inbox.isEmpty()); assertFalse(inline.graph.parsedInner().isDone());
        inline.attach(); inline.drainAll();
        assertSame(inline.value, inline.returned.join()); assertSame(inline.value, inline.holderFuture.join().orElseThrow(AssertionError::new));
        assertEquals(1, inline.applyCalls); assertEquals(1, inline.publicationCalls);

        Fixture terminalBeforeArm = new Fixture(false, Runnable::run, false, false);
        terminalBeforeArm.graph.superseded(); terminalBeforeArm.attach();
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, terminalBeforeArm.holderFuture.join());
        assertFalse(terminalBeforeArm.graph.retainsInputs());
    }

    @Test void fut10ABeforeClaimUsesIndependentWorkers() throws Exception {
        Fixture f = new Fixture(false); f.completePoi(); f.parsedSource.complete(Optional.empty()); Item stale = f.inbox.remove();
        f.move(f.b, 2);
        try (ExecutorService workerA = Executors.newSingleThreadExecutor(r -> new Thread(r, "actual-A"));
             ExecutorService workerB = Executors.newSingleThreadExecutor(r -> new Thread(r, "actual-B"))) {
            assertEquals(ChunkStageAdmission.Result.STALE_ROUTE,
                    workerA.submit(() -> f.execute(stale)).get());
            f.pump.deliverPublished(stale.stage()); Item current = f.inbox.remove();
            assertSame(stale.stage(), current.stage());
            assertEquals(ChunkStageAdmission.Result.APPLIED,
                    workerB.submit(() -> f.execute(current)).get());
        }
        f.drainAll(); assertEquals(1, f.applyCalls); assertEquals("actual-B", f.applyThread);
        assertEquals(f.b, f.applyOwner); assertSame(f.value, f.returned.join());
    }

    @Test void fut11AdjacentRecoveryOnce() {
        RuntimeException original = new RuntimeException("eligible");
        Fixture returning = new Fixture(false); returning.applyFailure = original; returning.completePoi();
        returning.parsedSource.complete(Optional.empty());
        assertEquals(ChunkStageAdmission.Result.FAILED_VANILLA, returning.executeOne(true));
        returning.move(returning.b, 2);
        assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, returning.executeOne(true));
        returning.drainAll(); assertEquals(1, returning.recoveryCalls); assertSame(returning.recoveryValue, returning.returned.join());
        assertSame(original, rootCause(returning.recoveryInput));

        Fixture throwing = new Fixture(false); throwing.applyFailure = original; throwing.recoveryFailure = new AssertionError("recovery");
        throwing.succeed(); assertEquals(1, throwing.recoveryCalls); assertTrue(throwing.holderFuture.join().getError() != null);
    }

    @Test void fut12NoControlCorruption() {
        Fixture superseded = new Fixture(false); superseded.completePoi(); superseded.parsedSource.complete(Optional.empty());
        superseded.graph.superseded(); assertSame(GenerationChunkHolder.UNLOADED_CHUNK, superseded.holderFuture.join());
        assertEquals(0, superseded.recoveryCalls); assertEquals(0, superseded.publicationCalls);

        Fixture protocol = new Fixture(false); protocol.applyFailure = new ChunkProtocolException("binding breach"); protocol.succeed();
        assertEquals(0, protocol.recoveryCalls); assertTrue(protocol.barrier.tripped());

        Fixture ordinaryCancel = new Fixture(false); ordinaryCancel.completePoi(); ordinaryCancel.parsedSource.cancel(false); ordinaryCancel.drainAll();
        assertEquals(1, ordinaryCancel.recoveryCalls); assertEquals(0, ordinaryCancel.terminals.size());
    }

    @Test void fut13CancellationShapes() {
        Fixture upstream = new Fixture(false); upstream.completePoi(); upstream.parsedSource.cancel(false); upstream.drainAll();
        assertEquals(1, upstream.recoveryCalls); assertInstanceOf(CancellationException.class, rootCause(upstream.recoveryInput));

        Fixture inner = new Fixture(false); inner.graph.parsedInner().cancel(false); inner.succeed();
        assertEquals(1, inner.applyCalls); assertEquals(1, inner.recoveryCalls); assertFalse(inner.returned.isCancelled());

        Fixture mapper = new Fixture(false); mapper.applyFailure = new CancellationException("mapper"); mapper.succeed();
        assertEquals(1, mapper.recoveryCalls); assertFalse(mapper.returned.isCancelled());

        Fixture directD = new Fixture(false); assertTrue(directD.graph.dependent().cancel(false)); directD.completePoi(); directD.parsedSource.complete(Optional.empty()); directD.drainAll();
        assertTrue(directD.graph.dependent().isCancelled()); assertFalse(directD.parsedSource.isCancelled()); assertEquals(1, directD.recoveryCalls);

        Fixture publicR = new Fixture(false); assertTrue(publicR.returned.cancel(false)); publicR.succeed();
        assertTrue(publicR.returned.isCancelled()); assertFalse(publicR.graph.dependent().isCancelled()); assertEquals(1, publicR.applyCalls);

        Fixture publicH = new Fixture(false); assertTrue(publicH.holderFuture.cancel(false)); publicH.succeed();
        assertTrue(publicH.holderFuture.isCancelled()); assertFalse(publicH.returned.isCancelled()); assertEquals(1, publicH.applyCalls);
    }

    @Test void fut14ReentrancyIdentity() {
        Fixture a = new Fixture(false); AtomicReference<Fixture> nested = new AtomicReference<>();
        a.duringApply = () -> nested.set(new Fixture(false));
        a.succeed(); Fixture b = nested.get(); assertNotNull(b); b.succeed();
        assertNotSame(a.graph.token(), b.graph.token()); assertNotEquals(a.graph.token().lifecycle(), b.graph.token().lifecycle());
        assertNotSame(a.returned, b.returned); assertNotSame(a.holder, b.holder);

        ChunkPrerequisiteServices shared = new ChunkPrerequisiteServices(new WorldId("same-coordinate"), "same", 1);
        RoutingKey key = new RoutingKey(shared.world(), 7, 7); ChunkHolder h1 = actualHolder(new ChunkPos(7, 7));
        var l1 = shared.lifecycles().activateHolder(1, key, h1);
        var w1 = shared.works().beginOrResolveSharedWork(l1, new ChunkWorkRegistry.WorkReservation("generation-status", "EMPTY", 0));
        ChunkHolder h2 = actualHolder(new ChunkPos(7, 7)); var l2 = shared.lifecycles().activateHolder(1, key, h2);
        var w2 = shared.works().beginOrResolveSharedWork(l2, new ChunkWorkRegistry.WorkReservation("generation-status", "EMPTY", 0));
        assertNotEquals(l1, l2); assertNotEquals(w1, w2); assertFalse(shared.lifecycles().isCurrent(l1)); assertTrue(shared.lifecycles().isCurrent(l2));
    }

    @Test void fut15SharedWork() throws Exception {
        WorldId world = new WorldId("shared-work"); ChunkPrerequisiteServices services = new ChunkPrerequisiteServices(world, "shared", 1);
        ChunkHolder holder = actualHolder(new ChunkPos(9, 9)); RoutingKey key = new RoutingKey(world, 9, 9);
        var lifecycle = services.lifecycles().activateHolder(1, key, holder);
        var work = services.works().beginOrResolveSharedWork(lifecycle, new ChunkWorkRegistry.WorkReservation("generation-status", "EMPTY", 0));
        var one = services.works().addDemand(work); var two = services.works().addDemand(work);
        AtomicReference<ChunkStatus> started = startedWork(holder); started.set(ChunkStatus.EMPTY);
        services.works().removeDemand(one); services.works().removeDemand(two);
        assertSame(ChunkStatus.EMPTY, started.get()); assertTrue(services.works().isCurrentWork(work));
        assertSame(work, services.works().beginOrResolveSharedWork(lifecycle,
                new ChunkWorkRegistry.WorkReservation("generation-status", "EMPTY", 0)));
    }

    @Test void fut16ClaimTerminalRace() {
        Fixture claimWins = new Fixture(false); claimWins.duringApply = claimWins.graph::shutdown; claimWins.succeed();
        assertEquals(1, claimWins.applyCalls); assertSame(GenerationChunkHolder.UNLOADED_CHUNK, claimWins.holderFuture.join());
        assertEquals(0, claimWins.recoveryCalls); assertEquals(0, claimWins.publicationCalls);

        Fixture shutdownWins = new Fixture(false); shutdownWins.completePoi(); shutdownWins.parsedSource.complete(Optional.empty());
        shutdownWins.graph.shutdown(); assertEquals(0, shutdownWins.applyCalls);
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, shutdownWins.holderFuture.join());

        Fixture topologyDuringClaim = new Fixture(true); topologyDuringClaim.lightInputs = List.of(
                new LightInputService.SectionInput(LightInputService.Layer.BLOCK, "x", 1),
                new LightInputService.SectionInput(LightInputService.Layer.SKY, "x", 2));
        topologyDuringClaim.duringApply = () -> topologyDuringClaim.move(topologyDuringClaim.b, 2);
        topologyDuringClaim.succeed(); assertEquals(1, topologyDuringClaim.applyCalls);
        assertEquals(List.of("LIGHT-RETAIN", "LIGHT-BLOCK", "LIGHT-SKY"),
                topologyDuringClaim.events.stream().filter(e -> e.startsWith("LIGHT-")).toList());
    }

    @Test void fut17RejectionCleanup() {
        Executor reject = ignored -> { throw new RejectedExecutionException("callback rejected"); };
        Fixture f = new Fixture(false, reject, false, false); f.poiSource.complete(Optional.empty()); f.attach();
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, f.holderFuture.join());
        f.parsedSource.complete(Optional.empty());
        assertEquals(0, f.applyCalls); assertEquals(0, f.recoveryCalls); assertTrue(f.callbackBarriers > 0);
        assertFalse(f.graph.retainsInputs()); assertEquals(0, f.services.receiptEntries());
        assertEquals(0, f.services.custody().retainedEntries()); assertEquals(0, f.services.lifecycles().retainedEntries());
        assertEquals(0, f.services.works().retainedEntries());
    }

    @Test void fut18ServiceOrdering() {
        Fixture f = new Fixture(true); f.lightInputs = List.of(
                new LightInputService.SectionInput(LightInputService.Layer.BLOCK, "s0", 1),
                new LightInputService.SectionInput(LightInputService.Layer.SKY, "s0", 2));
        f.succeed();
        assertEquals(List.of("POI", "READ", "LIGHT-RETAIN", "LIGHT-BLOCK", "LIGHT-SKY", "PARSED", "PUBLISH"), f.events);
        assertTrue(f.poiReceiptSeenAtRead); assertTrue(f.applyHadLease); assertTrue(f.publicationHadLease);
        assertNotEquals(f.graph.parsedStage(), f.graph.publicationStage());
        assertEquals(0, f.services.receiptEntries()); assertEquals(0, f.services.metadata().entries());
        assertEquals(0, f.services.custody().retainedEntries()); assertFalse(f.graph.retainsInputs());
    }

    /** Characterizes the OPEN defect, not the desired cleanup contract. Replace 2/68 with 0 when fixed. */
    @Test void missingRouteCancellationRecreatesTwoTerminalCustodyEntriesAfterCleanup() {
        int retained = 0;
        for (int occurrence = 0; occurrence < 34; occurrence++) {
            Fixture f = new Fixture(false);
            f.expectMissingRoute = true;
            f.directory.publish(new TopologyGeneration(2), Map.of());
            // The combined parsed edge is ready as soon as POI cancellation completes it.
            f.parsedSource.complete(Optional.empty());
            f.poiSource.complete(Optional.empty());
            assertSame(GenerationChunkHolder.UNLOADED_CHUNK, f.holderFuture.join());
            assertTrue(f.returned.isCompletedExceptionally());
            assertTrue(f.graph.dependent().isDone());
            assertTrue(f.graph.poiInner().isDone());
            assertTrue(f.graph.parsedInner().isDone());
            assertTrue(f.graph.recoveryInner().isDone());
            assertTrue(f.graph.publicationInner().isDone());
            assertEquals(1, f.unloadCalls);
            assertEquals(List.of("POI_INSTALL:CANCELLED"), f.terminals);
            assertEquals(0, f.poiInstalls + f.applyCalls + f.recoveryCalls + f.publicationCalls);
            assertTrue(f.inbox.isEmpty());
            assertFalse(f.graph.retainsInputs());
            assertFalse(f.leaseA.hasActiveLease());
            assertFalse(f.leaseB.hasActiveLease());
            assertFalse(f.barrier.tripped());
            assertEquals(0, f.services.receiptEntries());
            assertEquals(0, f.services.metadata().entries());
            assertEquals(0, f.services.lifecycles().retainedEntries());
            assertEquals(0, f.services.works().retainedEntries());
            assertTrue(f.services.custody().stageState(f.graph.poiStage()).isEmpty());
            assertTrue(f.services.custody().stageState(f.graph.parsedStage()).isEmpty());
            assertEquals(ChunkCustodyModel.StageState.CANCELLED,
                    f.services.custody().stageState(f.graph.recoveryStage()).orElseThrow());
            assertEquals(ChunkCustodyModel.StageState.CANCELLED,
                    f.services.custody().stageState(f.graph.publicationStage()).orElseThrow());
            f.graph.shutdown(); f.services.shutdown();
            assertEquals(2, f.services.custody().retainedEntries(), "known orphan StageId entries survive shutdown");
            retained += f.services.custody().retainedEntries();
        }
        assertEquals(68, retained, "matches the preserved 34-cancellation server log; cleanup is still OPEN");
    }

    private static final class Fixture implements ChunkLoadGraph.Environment {
        private static long fixtureSequence;
        final WorldId world = new WorldId("future-" + (++fixtureSequence));
        final ChunkPrerequisiteServices services = new ChunkPrerequisiteServices(world, "future", 1);
        final ChunkPos position = new ChunkPos(4, -4); final RoutingKey key = new RoutingKey(world, 4, -4);
        final RegionId a = new RegionId(world, "FUT-A"), b = new RegionId(world, "FUT-B");
        final RegionLeaseManager leaseA = new RegionLeaseManager(a), leaseB = new RegionLeaseManager(b);
        final RegionDirectory directory = new RegionDirectory(); final FailureBarrier barrier = new FailureBarrier();
        final ChunkHolder holder = actualHolder(position); final ChunkCustodyModel.LifecycleId lifecycle;
        final ChunkCustodyModel.WorkId work; final ChunkLoadGraph graph; final ChunkStageExecutor executor;
        final ChunkStageDeliveryPump pump; final ArrayDeque<Item> inbox = new ArrayDeque<>();
        final CompletableFuture<Object> poiSource = new CompletableFuture<>();
        final CompletableFuture<Optional<SerializableChunkData>> parsedSource = new CompletableFuture<>();
        final ChunkAccess value = allocate(ProtoChunk.class), recoveryValue = allocate(ProtoChunk.class);
        final SerializableChunkData serialized = allocate(SerializableChunkData.class);
        final Executor callbackExecutor; final boolean reload;
        CompletableFuture<ChunkAccess> returned; CompletableFuture<ChunkResult<ChunkAccess>> holderFuture;
        List<LightInputService.SectionInput> lightInputs = List.of(); final List<String> events = new ArrayList<>();
        final List<String> terminals = new ArrayList<>(); Throwable applyFailure, recoveryFailure, recoveryInput;
        Runnable duringApply; RegionId applyOwner; String applyThread;
        int poiInstalls, applyCalls, recoveryCalls, publicationCalls, unloadCalls, callbackBarriers;
        long batchSequence; boolean applyHadLease, publicationHadLease, poiReceiptSeenAtRead, expectMissingRoute;
        CompletableFuture<ChunkResult<ChunkAccess>> oldStatus;

        Fixture(boolean reload) { this(reload, Runnable::run, true, false); }
        Fixture(boolean reload, Executor callbackExecutor, boolean arm, boolean precompleted) {
            this.reload = reload; this.callbackExecutor = callbackExecutor;
            move(a, 1); lifecycle = services.lifecycles().activateHolder(1, key, holder);
            work = services.works().beginOrResolveSharedWork(lifecycle,
                    new ChunkWorkRegistry.WorkReservation("generation-status", "EMPTY", 0));
            graph = new ChunkLoadGraph(this, new ChunkLoadGraph.Token(lifecycle, work, holder, new Object()));
            executor = new ChunkStageExecutor(new ChunkStageAdmission(services.custody(), directory,
                    Map.of(a, leaseA, b, leaseB), barrier));
            pump = new ChunkStageDeliveryPump(directory, (route, stage) -> {
                inbox.addLast(new Item(route, stage)); return ChunkStageOwnerGateway.Delivery.QUEUED;
            });
            if (precompleted) {
                assertEquals(PoiInstallService.Claim.WON, services.poi().claim(graph.poiId()));
                assertEquals(PoiInstallService.Settlement.INSTALLED, services.poi().installed(graph.poiId()));
                poiSource.complete(Optional.empty()); parsedSource.complete(input());
            }
            CompletableFuture<Void> poi = graph.registerPoi(poiSource, callbackExecutor);
            CompletableFuture<Optional<SerializableChunkData>> upstream = parsedSource.thenCombine(poi, (parsed, ignored) -> parsed);
            returned = graph.register(upstream, callbackExecutor);
            if (arm) attach();
        }
        void attach() { if (holderFuture == null) holderFuture = graph.attachHolder(returned, holder); }
        Optional<SerializableChunkData> input() { return reload ? Optional.of(serialized) : Optional.empty(); }
        void completePoi() { poiSource.complete(Optional.empty()); while (!inbox.isEmpty()) executeOne(true); }
        void succeed() { completePoi(); parsedSource.complete(input()); drainAll(); }
        void drainAll() {
            int guard = 0;
            while (!inbox.isEmpty()) { executeOne(true); if (++guard > 1000) fail("delivery did not settle"); }
        }
        ChunkStageAdmission.Result executeOne(boolean reroute) {
            Item item = inbox.removeFirst(); ChunkStageAdmission.Result result = execute(item);
            if (reroute && (result == ChunkStageAdmission.Result.STALE_ROUTE || result == ChunkStageAdmission.Result.NOT_READY))
                pump.deliverPublished(item.stage());
            return result;
        }
        @SuppressWarnings({"rawtypes", "unchecked"}) ChunkStageAdmission.Result execute(Item item) {
            return executor.execute((PendingVanillaStage) item.stage(), item.route().region(), item.route().generation());
        }
        void move(RegionId owner, long generation) {
            TopologyGeneration next = new TopologyGeneration(generation);
            directory.publish(next, Map.of(key, new RegionRoute(owner, next, new OwnerEpoch(0))));
        }
        void installOldStatusSlot() throws Exception {
            oldStatus = new CompletableFuture<>(); statusSlots().set(ChunkStatus.EMPTY.getIndex(), oldStatus);
        }
        @SuppressWarnings("unchecked") AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> statusSlots() throws Exception {
            Field field = GenerationChunkHolder.class.getDeclaredField("futures"); field.setAccessible(true);
            return (AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>) field.get(holder);
        }

        @Override public ChunkPrerequisiteServices services() { return services; }
        @Override public void enqueue(PendingVanillaStage<?, ?> pending) {
            var delivery = pump.publishAndDeliver(pending);
            if (expectMissingRoute) {
                assertEquals(ChunkStageOwnerGateway.Delivery.REJECTED_ROUTE, delivery);
                pending.control(ChunkStageAdmission.Result.CANCELLED); // Same redundant terminal call as LoadEnvironment.
            } else assertEquals(ChunkStageOwnerGateway.Delivery.QUEUED, delivery);
        }
        @Override public void requireHolder(ChunkLoadGraph.Token token) {
            if (token.holder() != holder || !services.lifecycles().observeSameHolder(token.lifecycle(), holder))
                throw new ChunkProtocolException("holder evidence changed");
        }
        @Override public boolean poiAlreadyInstalled(ChunkLoadGraph.Token token, CompletableFuture<?> source) { return false; }
        @Override public void installPoi(ChunkLoadGraph.Token token, ChunkLoadGraph.PoiInput input, ChunkStageClaim claim) {
            requireHolder(token); claim.require(claim.stage()); poiInstalls++; events.add("POI");
        }
        @Override public LightInputService.BatchId prepareReload(ChunkLoadGraph source, SerializableChunkData data) {
            LightInputService.BatchId id = new LightInputService.BatchId(lifecycle, work, graph.parsedStage(), batchSequence++);
            assertTrue(services.light().register(id, !lightInputs.isEmpty(), lightInputs)); return id;
        }
        @Override public ChunkAccess apply(ChunkLoadGraph.Token token, Optional<SerializableChunkData> data,
                                           LightInputService.BatchId batch, ChunkStageClaim claim) throws Throwable {
            requireHolder(token); applyCalls++; events.add("READ"); applyHadLease = OwnershipContext.current().isPresent();
            applyOwner = OwnershipContext.current().orElseThrow().region(); applyThread = Thread.currentThread().getName();
            poiReceiptSeenAtRead = !reload || services.poi().receipt(graph.poiId()).isPresent();
            if (duringApply != null) duringApply.run();
            if (reload) {
                for (var occurrence : services.light().snapshot(batch).orElseThrow().occurrences()) {
                    events.add("LIGHT-" + occurrence.id().kind());
                    services.light().invoke(claim, occurrence.id(), LightInputService.Insertion::inserted);
                }
                services.light().sealRead(claim, batch);
            }
            if (applyFailure != null) throwAny(applyFailure);
            events.add("PARSED"); return value;
        }
        @Override public ChunkAccess recover(ChunkLoadGraph.Token token, Throwable failure, ChunkStageClaim claim) throws Throwable {
            requireHolder(token); claim.require(claim.stage()); recoveryCalls++; recoveryInput = failure;
            if (recoveryFailure != null) throwAny(recoveryFailure); return recoveryValue;
        }
        @Override public ChunkResult<ChunkAccess> publish(ChunkLoadGraph.Token token, ChunkLoadGraph.HolderInput input, ChunkStageClaim claim) {
            requireHolder(token); claim.require(claim.stage()); publicationCalls++; events.add("PUBLISH");
            publicationHadLease = OwnershipContext.current().isPresent();
            return input.failure() == null ? ChunkResult.of(input.value()) : ChunkResult.error(input.failure().toString());
        }
        @Override public void unload(ChunkLoadGraph.Token token) {
            unloadCalls++;
            if (oldStatus != null && !oldStatus.isDone()) {
                try {
                    Method method = GenerationChunkHolder.class.getDeclaredMethod("failAndClearPendingFuture", int.class, CompletableFuture.class);
                    method.setAccessible(true); method.invoke(holder, ChunkStatus.EMPTY.getIndex(), oldStatus);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
        }
        @Override public void terminal(ChunkLoadGraph source, ChunkCustodyModel.StageId stage,
                                       ChunkStageAdmission.Result reason, Throwable failure) { terminals.add(stage.kind() + ":" + reason); }
        @Override public void barrier(ChunkLoadGraph source, ChunkCustodyModel.StageId stage, Throwable failure) { callbackBarriers++; }
        @Override public void settled(ChunkLoadGraph source) { services.releaseFinishedWork(work, holder); }
    }

    private record Item(RegionRoute route, PendingVanillaStage<?, ?> stage) { }
    private static ChunkHolder actualHolder(ChunkPos pos) {
        return new ChunkHolder(pos, ChunkLevel.byStatus(ChunkStatus.EMPTY), LevelHeightAccessor.create(0, 48), null, null, null);
    }
    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) {
        try { return (T) MinecraftLightReadFixture.allocate(type); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked") private static AtomicReference<ChunkStatus> startedWork(GenerationChunkHolder holder) throws Exception {
        Field field = GenerationChunkHolder.class.getDeclaredField("startedWork"); field.setAccessible(true);
        return (AtomicReference<ChunkStatus>) field.get(holder);
    }
    private static Throwable rootCause(Throwable value) {
        Throwable current = value;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }
    private static Throwable deepestOrSelf(Throwable observed, Throwable expected) {
        if (observed == expected) return expected;
        return rootCause(observed);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void throwAny(Throwable failure) throws E { throw (E) failure; }
}
