package dev.phoenium.chunk;

import java.util.concurrent.CompletionException;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Method;
import dev.phoenium.core.*;
import dev.phoenium.region.*;
import dev.phoenium.topology.*;
import dev.phoenium.scheduler.*;
import net.minecraft.server.level.*;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled world endpoints and executor; holder reservation, generation task and async FULL are original. */
class FullProductionRegistrationTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void winningReservationOriginalAsyncFullAdmitsPreparationWithoutPublishingFull(boolean inline) throws Exception {
        var f = new FullRegistrationFixture(inline);
        f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var holder = f.holder(ChunkPos.ZERO);
        var graph = f.runtime.graph(holder);
        assertEquals(1, f.runtime.measurements().get("captures"));
        assertNull(((FullHolderEvidence) (Object) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex()),
                "winning applyStep does not create a FULL subscriber slot");
        assertFalse(graph.conversion().isDone());
        assertNotSame(graph.conversion(), graph.returned(), "original ChunkStep.apply adds the generation-completion dependent");
        f.drain();
        var receipt = graph.prepared().orElseThrow();
        assertSame(holder, receipt.holder()); assertEquals(graph.work, receipt.work());
        assertSame(receipt.actual(), ((net.minecraft.world.level.chunk.ImposterProtoChunk)
                holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN)).getWrapped());
        assertEquals(false, field(receipt.actual(), LevelChunk.class, "loaded"));
        assertNotNull(field(receipt.actual(), LevelChunk.class, "postLoad"));
        assertTrue(f.world.inserted.isEmpty());
        assertFalse(graph.conversion().isDone()); assertFalse(graph.returned().isDone()); assertFalse(graph.adjacent().isDone());
        assertNull(holder.getChunkIfPresent(ChunkStatus.FULL));
        assertNull(f.world.cache.getChunkNow(0, 0), "the original ordinary ready-cache getter cannot expose preparation");
        assertTrue(Arrays.stream((ChunkAccess[]) field(f.world.cache, ServerChunkCache.class, "lastChunk")).allMatch(Objects::isNull));
        assertNull(holder.getTickingChunk()); assertNull(holder.getChunkToSend());
        assertFalse(graph.missingFinishingBindings().isEmpty());
        assertEquals(1, f.runtime.measurements().get("preparationCalls"));
        assertFalse(f.owner.leases().hasActiveLease());
        f.stop();
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, graph.adjacent().join());
        assertEquals(ChunkStageAdmission.Result.SHUTDOWN, assertInstanceOf(FullRegistrationRuntime.Stopped.class,
                assertThrows(CompletionException.class, graph.conversion()::join).getCause()).reason);
        assertEquals(0, f.runtime.measurements().get("graphs"));
        assertEquals(0, f.runtime.measurements().get("generationHolders"));
        assertEquals(0, f.services.custody().retainedEntries());
        assertEquals(0, f.services.lifecycles().retainedEntries());
        assertEquals(0, f.services.works().retainedEntries());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inMemoryImposterReusesTheExactInputAndKeepsOriginalFutureIdentity(boolean inline) throws Exception {
        var f = new FullRegistrationFixture(inline);
        LevelChunk actual = new LevelChunk(f.world.level, f.world.proto, ignored -> fail("post-load must remain deferred"));
        var input = new ImposterProtoChunk(actual, false);
        f.inputs.put(ChunkPos.ZERO.pack(), input);
        f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var holder = f.holder(ChunkPos.ZERO); var graph = f.runtime.graph(holder);
        assertSame(graph.conversion(), graph.returned(), "reload ChunkStep.apply returns the exact wrapper future");
        var lower = holder.getAllFutures().getFirst().getSecond();
        f.drain();
        assertSame(actual, graph.prepared().orElseThrow().actual());
        assertSame(input, holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN));
        assertSame(lower, holder.getAllFutures().getFirst().getSecond(), "Imposter branch never replaces the holder");
        assertEquals(false, field(actual, LevelChunk.class, "loaded"));
        assertNotNull(field(actual, LevelChunk.class, "postLoad"));
        assertFalse(graph.returned().isDone());
        f.stop(); assertReleased(f);
    }

    @Test void actualProducerCompletionReentryParksFullUntilItsWriterReleaseWakesTheSameWork() throws Exception {
        var f = new FullRegistrationFixture(true);
        f.delayedPosition = ChunkPos.ZERO; f.delayedStatus = ChunkStatus.SPAWN;
        var task = f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        assertNotNull(f.producer); assertFalse(f.producer.isDone());
        var waiting = task.runUntilWait();
        var observed = new java.util.concurrent.atomic.AtomicReference<FullRegistrationRuntime.Graph>();
        var reentered = waiting.thenRun(() -> {
            // Original holder handle has published SPAWN and completed its adjacent result.
            // Its older raw-future writer-release callback has not executed yet.
            f.resume(task);
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); observed.set(graph);
            f.drain();
            assertTrue(graph.prepared().isEmpty()); assertFalse(f.owner.leases().hasActiveLease());
            assertEquals(0, f.runtime.measurements().get("preparationCalls"));
        });
        f.producer.complete(f.input(ChunkPos.ZERO));
        reentered.join();
        var graph = observed.get(); assertNotNull(graph);
        f.drain();
        assertSame(graph, f.runtime.graph(graph.holder)); assertTrue(graph.prepared().isPresent());
        assertTrue(((FullHolderEvidence) graph.holder).phoenium$generationRefs().get() > 0,
                "FULL retains its own inputs until its pending result settles");
        assertFalse(graph.returned().isDone());
        f.stop(); assertReleased(f);
    }

    @Test void anOldGenerationStatusCannotWinANewConflictingWriterAfterFullWasReserved() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
        var neighbor = f.holder(new ChunkPos(1, 0)); int calls = f.mapCalls;
        var prior = apply(neighbor, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), f,
                StaticCache2D.create(1, 0, 1, (x, z) -> f.holder(new ChunkPos(x, z))));
        assertTrue(prior.isDone()); assertEquals(calls, f.mapCalls);
        f.drain(); assertTrue(graph.prepared().isPresent());
        f.stop(); assertReleased(f);
    }
    @Test void nonWinningRepeatCreatesOnlyItsSubscriberSlotAndCannotRegisterAnotherPreparation() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var holder = f.holder(ChunkPos.ZERO); var graph = f.runtime.graph(holder);
        int calls = f.mapCalls;
        var repeated = apply(holder, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL), f,
                StaticCache2D.create(0, 0, 0, (x, z) -> holder));
        assertEquals(calls, f.mapCalls); assertEquals(1, f.runtime.measurements().get("registrations"));
        assertSame(repeated, ((FullHolderEvidence) (Object) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex()));
        f.drain(); assertFalse(repeated.isDone()); assertFalse(graph.returned().isDone());
        f.stop(); assertSame(GenerationChunkHolder.UNLOADED_CHUNK, repeated.join()); assertReleased(f);
    }

    @Test void interleavedWorksUseExactReturnedReferencesAndDistinctReceipts() throws Exception {
        var f = new FullRegistrationFixture(false);
        var x = ChunkPos.ZERO; var y = new ChunkPos(2, 0);
        f.start(x, ChunkStatus.FULL); f.start(y, ChunkStatus.FULL);
        var a = f.runtime.graph(f.holder(x)); var b = f.runtime.graph(f.holder(y));
        assertNotSame(a.returned(), b.returned()); assertNotEquals(a.work, b.work);
        f.registrations.removeLast().run(); f.drain();
        assertNotSame(a.prepared().orElseThrow().actual(), b.prepared().orElseThrow().actual());
        assertEquals(2, f.runtime.measurements().get("captures"));
        assertNull(FullRegistrationRuntime.attach(f, new CompletableFuture<>(), a.holder,
                ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL), (chunk, error) -> fail("foreign future handler")));
        f.stop(); assertReleased(f);
    }

    @Test void lateOldRegistrationCannotAttachToSameCoordinateReplacementLifecycle() throws Exception {
        var f = new FullRegistrationFixture(false);
        var oldTask = f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var old = f.runtime.graph(f.holder(ChunkPos.ZERO));
        Runnable late = f.registrations.remove();
        f.holders.remove(ChunkPos.ZERO.pack()); f.inputs.remove(ChunkPos.ZERO.pack());
        f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var fresh = f.runtime.graph(f.holder(ChunkPos.ZERO));
        assertNotEquals(old.lifecycle, fresh.lifecycle); assertNotEquals(old.work, fresh.work);
        late.run(); f.resume(oldTask); f.drain();
        assertTrue(old.prepared().isEmpty()); assertTrue(fresh.prepared().isPresent());
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, old.adjacent().join());
        assertTrue(f.services.lifecycles().isCurrent(fresh.lifecycle));
        late.run(); f.drain(); assertEquals(1, f.runtime.measurements().get("preparationCalls"));
        f.stop(); assertReleased(f);
    }

    @Test void exactLowerSlotReplacementIsRevalidatedImmediatelyBeforeClaim() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
        var other = new LevelChunk(f.world.level, f.world.proto, null);
        graph.holder.replaceProtoChunk(new ImposterProtoChunk(other, false));
        f.drain();
        assertTrue(graph.prepared().isEmpty()); assertEquals(0, f.runtime.measurements().get("preparationCalls"));
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, graph.adjacent().join());
        f.stop(); assertReleased(f);
    }

    @Test void finishingWithoutRealServicesCannotClaimEvenWhenQueuedWithAValidPreparedInput() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
        graph.finishing.publishReady();
        var executor = new ChunkStageExecutor(new ChunkStageAdmission(f.services.custody(), f.directory,
                Map.of(f.ownerId, f.owner.leases()), f.barrier));
        assertEquals(ChunkStageAdmission.Result.NOT_READY, executor.execute(graph.finishing, f.ownerId, new TopologyGeneration(1)));
        assertEquals(ChunkCustodyModel.StageState.QUEUED, graph.finishing.state().orElseThrow());
        assertTrue(graph.missingFinishingBindings().contains("finishing service reservation"));
        assertTrue(f.world.inserted.isEmpty()); assertFalse(graph.conversion().isDone());
        assertFalse(f.owner.leases().hasActiveLease());
        f.stop(); assertReleased(f);
    }

    @Test void duplicateDeliveryAndCompletedPreparationReentryDoNotReconstructOrHoldTheClaim() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
        graph.preparation.outcome().thenRun(() -> {
            assertFalse(f.services.custody().gate().isHeldByCurrentThread());
            assertFalse(f.owner.leases().hasActiveLease());
            f.runtime.pump.deliverPublished(graph.preparation);
        });
        f.drain(); var actual = graph.prepared().orElseThrow().actual();
        f.runtime.pump.deliverPublished(graph.preparation); f.drain();
        assertSame(actual, graph.prepared().orElseThrow().actual());
        assertEquals(1, f.runtime.measurements().get("preparationCalls"));
        f.stop(); assertReleased(f);
    }

    @Test void authenticShutdownBeforeOriginalExecutorRunsSettlesAndLateCallbackCannotResurrect() throws Exception {
        var f = new FullRegistrationFixture(false); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); Runnable late = f.registrations.remove();
        f.stop(); assertEquals(1, f.runtime.measurements().get("graphs"),
                "the withheld original executor callback has not acknowledged shutdown yet");
        late.run(); f.drain();
        assertTrue(graph.prepared().isEmpty()); assertEquals(0, f.runtime.measurements().get("preparationCalls"));
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, graph.adjacent().join()); assertReleased(f);
    }

    @Test void successfulReceiptSurvivesRetirementUntilTheExactConsumerAcknowledges() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var pin = graph.retainPrepared();
        var actual = graph.prepared().orElseThrow().actual();
        f.stop(); assertEquals(1, f.runtime.measurements().get("graphs"));
        assertSame(actual, graph.prepared().orElseThrow().actual());
        pin.close(); pin.close(); assertReleased(f); assertTrue(graph.prepared().isEmpty());
    }

    @Test void preparationReceiptIsInstalledBeforeAnInlineConsumerCanRetainIt() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
        var pin = new java.util.concurrent.atomic.AtomicReference<AutoCloseable>();
        var consumer = graph.preparation.outcome().thenRun(() -> {
            assertFalse(f.owner.leases().hasActiveLease());
            assertTrue(graph.prepared().isPresent()); pin.set(graph.retainPrepared());
        });
        f.drain(); consumer.join(); f.stop();
        assertEquals(1, f.runtime.measurements().get("graphs"));
        pin.get().close(); assertReleased(f);
    }

    @Test void pendingTopologyParksTheRealPreparationAndStaleDeliveryResolvesCurrentRoute() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
        var runtimes = Map.of(f.ownerId, f.owner);
        var topology = new TopologyCoordinator(f.directory, runtimes,
                new OwnerGateway(f.directory, runtimes, new RegionScheduler(runtimes, f.directory, f.barrier)));
        var key = new RoutingKey(f.worldId, 0, 0);
        topology.request(new TopologyCoordinator.Transition(Set.of(f.ownerId), Map.of(key, f.ownerId)));
        assertTrue(f.runtime.pumpOne()); assertFalse(f.runtime.pumpOne());
        assertEquals(1, f.runtime.pump.parkedCount()); assertTrue(graph.prepared().isEmpty());
        assertTrue(topology.advance()); f.drain(); assertTrue(graph.prepared().isPresent());
        assertFalse(f.barrier.tripped()); f.stop(); assertReleased(f);
    }

    static CompletableFuture<?> apply(GenerationChunkHolder holder, ChunkStep step, GeneratingChunkMap map,
                                       StaticCache2D<GenerationChunkHolder> cache) throws Exception {
        Method method = GenerationChunkHolder.class.getDeclaredMethod("applyStep", ChunkStep.class, GeneratingChunkMap.class, StaticCache2D.class);
        method.setAccessible(true); return (CompletableFuture<?>) method.invoke(holder, step, map, cache);
    }

    @Test void realSulfurSetLevelUsesTheActualClockWriterBindingAndReleasesItsReadTurn() throws Exception {
        var f = new FullRegistrationFixture(true);
        var data = new PrimaryLevelData(new net.minecraft.world.level.LevelSettings("clock", net.minecraft.world.level.GameType.CREATIVE,
                net.minecraft.world.level.LevelSettings.DifficultySettings.DEFAULT, true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                PrimaryLevelData.SpecialWorldProperty.NONE, com.mojang.serialization.Lifecycle.stable());
        set(f.world.level, Level.class, "levelData", data);
        f.world.gameTimeEndpoint = args -> data.getGameTime();
        data.setGameTime(137L);
        var clock = ((FullPreparationClockAccess) (Object) data).phoenium$preparationClock();
        assertEquals(1, clock.revision());
        var pos = new BlockPos(8, 8, 8); var state = Blocks.POTENT_SULFUR.defaultBlockState();
        f.world.proto.getSection(0).setBlockState(8, 8, 8, state);
        var sulfur = new PotentSulfurBlockEntity(pos, state);
        f.world.proto.setBlockEntity(sulfur);
        f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var holder = f.holder(ChunkPos.ZERO); var graph = f.runtime.graph(holder);
        holder.afterReplace = () -> assertEquals(1, clock.activeReaders(), "the constructor holds its actual clock read turn");
        f.drain();
        assertSame(sulfur, graph.prepared().orElseThrow().actual().getBlockEntities().get(pos));
        assertEquals(137L, field(sulfur, PotentSulfurBlockEntity.class, "eruptionTick"));
        data.setGameTime(138L); assertEquals(2, clock.revision());
        assertFalse(f.barrier.tripped()); f.stop(); assertReleased(f); assertEquals(0, clock.retainedEntries());
    }

    @Test void missingActualClockBindingMakesTheSulfurConstructorIneligible() throws Exception {
        var f = new FullRegistrationFixture(true);
        var pos = new BlockPos(8, 8, 8); var state = Blocks.POTENT_SULFUR.defaultBlockState();
        f.world.proto.getSection(0).setBlockState(8, 8, 8, state);
        var sulfur = new PotentSulfurBlockEntity(pos, state); f.world.proto.setBlockEntity(sulfur);
        f.start(ChunkPos.ZERO, ChunkStatus.FULL); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); f.drain();
        assertTrue(graph.prepared().isEmpty()); assertNull(sulfur.getLevel());
        assertEquals(0, f.runtime.measurements().get("preparationCalls")); f.stop(); assertReleased(f);
    }

    @Test void originalHolderReplacementPrefixAndThrowableSurviveWithoutSuccessfulReceiptOrRetry() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var holder = f.holder(ChunkPos.ZERO); var graph = f.runtime.graph(holder);
        var exact = new IllegalStateException("failure after original holder replacement");
        var delayed = net.minecraft.util.thread.BlockableEventLoop.class.getDeclaredField("delayedCrash"); delayed.setAccessible(true);
        Object previous = delayed.get(null);
        try {
            holder.afterReplace = () -> { throw exact; };
            f.drain();
            assertSame(exact, assertThrows(CompletionException.class, graph.conversion()::join).getCause());
            assertTrue(graph.prepared().isEmpty());
            assertInstanceOf(ImposterProtoChunk.class, holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN));
            assertEquals(1, holder.replacements);
            f.runtime.pump.deliverPublished(graph.preparation); f.drain();
            assertEquals(1, f.runtime.measurements().get("preparationCalls")); assertEquals(1, holder.replacements);
            assertNotNull(delayed.get(null), "the adjacent original handler retains vanilla delayed-crash behavior");
            f.stop(); assertReleased(f);
        } finally { delayed.set(null, previous); }
    }

    @Test void reentrantShutdownDuringOriginalReplacementCannotSettleWhileTheClaimStillOwnsThePrefix() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var holder = f.holder(ChunkPos.ZERO); var graph = f.runtime.graph(holder);
        holder.afterReplace = () -> {
            assertTrue(f.owner.leases().hasActiveLease());
            f.runtime.shutdown();
            assertFalse(graph.conversion().isDone()); assertFalse(graph.adjacent().isDone());
            assertEquals(1, f.runtime.measurements().get("graphs"));
        };
        f.drain();
        assertInstanceOf(ChunkStageOutcome.Applied.class, graph.preparation.outcome().join());
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, graph.adjacent().join());
        assertEquals(1, holder.replacements); f.stop(); assertReleased(f);
    }
    static void assertReleased(FullRegistrationFixture f) {
        assertEquals(0, f.runtime.measurements().get("graphs"));
        assertEquals(0, f.runtime.measurements().get("returnedIndex"));
        assertEquals(0, f.runtime.measurements().get("generationHolders"));
        assertEquals(0, f.runtime.measurements().get("deliveries"));
        assertEquals(0, f.services.custody().retainedEntries());
        assertEquals(0, f.services.lifecycles().retainedEntries());
        assertEquals(0, f.services.works().retainedEntries());
        assertFalse(f.owner.leases().hasActiveLease());
    }

    @Test void staleRouteRevalidationExecutesPreparationOnlyInTheNewCurrentOwner() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var holder = f.holder(ChunkPos.ZERO); var graph = f.runtime.graph(holder);
        var generation = new TopologyGeneration(2);
        f.directory.publish(generation, Map.of(), Map.of(f.worldId, new RegionRoute(f.otherId, generation, new OwnerEpoch(0))));
        holder.afterReplace = () -> {
            assertTrue(f.other.leases().hasActiveLease()); assertFalse(f.owner.leases().hasActiveLease());
        };
        assertTrue(f.runtime.pumpOne()); assertTrue(graph.prepared().isEmpty());
        f.drain(); assertTrue(graph.prepared().isPresent());
        assertEquals(1, holder.replacements); f.stop(); assertReleased(f);
    }

    @Test void completedGenerationRetentionIsReleasedAndLaterFullUsesItsActualExistingParentSlot() throws Exception {
        var f = new FullRegistrationFixture(true); f.start(ChunkPos.ZERO, ChunkStatus.SPAWN);
        assertEquals(0, f.runtime.measurements().get("generationHolders"));
        var holder = f.holder(ChunkPos.ZERO); var parent = holder.getAllFutures().get(ChunkStatus.SPAWN.getIndex()).getSecond();
        assertTrue(parent.isDone());
        f.start(ChunkPos.ZERO, ChunkStatus.FULL); var graph = f.runtime.graph(holder); f.drain();
        assertTrue(graph.prepared().isPresent()); assertSame(f.world.proto, parent.join().orElse(null));
        f.stop(); assertReleased(f);
    }

    @Test void winningStatusWithoutAnActualGenerationBorrowCannotRegisterPreparation() throws Exception {
        var f = new FullRegistrationFixture(true); var holder = f.holder(ChunkPos.ZERO);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> apply(holder,
                ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.EMPTY), f,
                StaticCache2D.create(0, 0, 0, (x, z) -> holder)));
        assertTrue(assertInstanceOf(ChunkProtocolException.class, failure.getCause()).getMessage().contains("no live generation borrow"));
        assertEquals(0, f.mapCalls); assertEquals(0, f.runtime.measurements().get("preparationCalls"));
        f.stop(); assertReleased(f);
    }

    @Test void explicitWorkCancellationBeforeRegistrationArmsRemainsDistinctFromSupersession() throws Exception {
        var f = new FullRegistrationFixture(false); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
        f.services.works().retireWork(graph.work, true); f.drain();
        var failure = assertInstanceOf(FullRegistrationRuntime.Stopped.class,
                assertThrows(CompletionException.class, graph.conversion()::join).getCause());
        assertEquals(ChunkStageAdmission.Result.CANCELLED, failure.reason);
        assertFalse(f.barrier.tripped()); assertTrue(graph.prepared().isEmpty()); f.stop(); assertReleased(f);
    }

    @Test void validFullWithoutRouteTripsBarrierAndDoesNotInventCancellation() throws Exception {
        var f = new FullRegistrationFixture(false); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
        f.directory.publish(new TopologyGeneration(2), Map.of()); f.drain();
        assertTrue(f.barrier.tripped()); assertTrue(graph.prepared().isEmpty());
        var failure = assertInstanceOf(FullRegistrationRuntime.Stopped.class,
                assertThrows(CompletionException.class, graph.returned()::join).getCause());
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, failure.reason);
        f.stop(); assertReleased(f);
    }

    @Test void completedReturnedFutureIsCorrelatedBeforeInlineAdjacentHandlerReentry() throws Exception {
        var f = new FullRegistrationFixture(true);
        var observed = new java.util.concurrent.atomic.AtomicReference<FullRegistrationRuntime.Graph>();
        f.beforeRegistration = () -> {
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); observed.set(graph);
            f.runtime.shutdown(); assertTrue(graph.conversion().isDone());
        };
        f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = observed.get(); assertNotNull(graph);
        assertSame(GenerationChunkHolder.UNLOADED_CHUNK, graph.adjacent().join());
        assertEquals(1, f.runtime.measurements().get("captures"));
        f.stop(); assertReleased(f);
    }

    @Test void rejectedOriginalExecutorSettlesExactPrivateBarrierAndEveryGenerationPin() throws Exception {
        var f = new FullRegistrationFixture(true);
        var observed = new java.util.concurrent.atomic.AtomicReference<FullRegistrationRuntime.Graph>();
        var exact = new RejectedExecutionException("controlled executor rejection");
        f.beforeRegistration = () -> { observed.set(f.runtime.graph(f.holder(ChunkPos.ZERO))); throw exact; };
        f.start(ChunkPos.ZERO, ChunkStatus.FULL);
        var graph = observed.get(); assertNotNull(graph); assertTrue(graph.prepared().isEmpty());
        var failure = assertInstanceOf(FullRegistrationRuntime.Stopped.class,
                assertThrows(CompletionException.class, graph.conversion()::join).getCause());
        assertSame(exact, failure.getCause()); assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, failure.reason);
        assertSame(exact, f.barrier.record().orElseThrow().cause());
        f.stop(); assertReleased(f);
    }

    @Test void constructorFailureRetainsTheOriginalBlockEntityPrefixAndCannotBeRetried() throws Exception {
        var f = new FullRegistrationFixture(true);
        var pos = new BlockPos(8, 8, 8); var state = Blocks.BEACON.defaultBlockState();
        f.world.proto.getSection(0).setBlockState(8, 8, 8, state);
        var exact = new IllegalStateException("constructor setLevel failure");
        int[] attempts = {0};
        var beacon = new BeaconBlockEntity(pos, state) {
            @Override public void setLevel(Level level) { super.setLevel(level); attempts[0]++; throw exact; }
        };
        f.world.proto.setBlockEntity(beacon);
        var delayed = net.minecraft.util.thread.BlockableEventLoop.class.getDeclaredField("delayedCrash"); delayed.setAccessible(true);
        Object previous = delayed.get(null);
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); f.drain();
            assertSame(exact, assertThrows(CompletionException.class, graph.conversion()::join).getCause());
            assertSame(f.world.level, beacon.getLevel()); assertEquals(1, attempts[0]); assertTrue(graph.prepared().isEmpty());
            assertSame(f.world.proto, graph.holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN));
            f.runtime.pump.deliverPublished(graph.preparation); f.drain(); assertEquals(1, attempts[0]);
            assertEquals(0, f.holder(ChunkPos.ZERO).replacements); f.stop(); assertReleased(f);
        } finally { delayed.set(null, previous); }
    }
}
