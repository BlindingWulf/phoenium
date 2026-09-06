package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import java.util.Map;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled source data/world endpoints; original asynchronous wrapper and admitted preparation. */
final class FullInitializedInputBindingTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    @Test void originalAsyncProtoPreparationFeedsTheRealFinishingInputPrerequisite() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var actual = graph.prepared().orElseThrow().actual();
            graph.finishing.publishReady();
            var executor = new ChunkStageExecutor(new ChunkStageAdmission(f.services.custody(), f.directory,
                    Map.of(f.ownerId, f.owner.leases()), f.barrier));
            assertEquals(ChunkStageAdmission.Result.NOT_READY,
                    executor.execute(graph.finishing, f.ownerId, new TopologyGeneration(1)));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.lastInputEvaluation().state());
            assertSame(actual, graph.lastInputEvaluation().receipt().targets().getFirst().actual());
            assertFalse(graph.missingFinishingBindings().contains("initialized-input discovery and query operation bindings"),
                    "The real evaluator must consume a separately satisfied initialized-input component");
            assertSame(actual, graph.prepared().orElseThrow().actual());
            assertFalse(graph.conversion().isDone()); assertFalse(graph.returned().isDone());
            assertTrue(f.world.inserted.isEmpty()); assertFalse(f.owner.leases().hasActiveLease());
        } finally { f.stop(); }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void originalSavedPairBindsBothConsumersBeforeEitherPublicFull(boolean reload, boolean reverse) throws Exception {
        var f = new FullRegistrationFixture(false);
        var x = ChunkPos.ZERO; var y = new ChunkPos(1, 0);
        try {
            saved(f, x, reload, tag(15.8, 8, 24000)); saved(f, y, reload, tag(16.2, 8, 24000));
            f.start(reverse ? y : x, ChunkStatus.FULL); f.start(reverse ? x : y, ChunkStatus.FULL);
            f.drain();
            var a = f.runtime.graph(f.holder(x)); var b = f.runtime.graph(f.holder(y));
            assertBound(f, a, Set.of(x, y)); assertBound(f, b, Set.of(x, y));
            assertSame(a.prepared().orElseThrow().actual(), target(b, x).actual());
            assertSame(b.prepared().orElseThrow().actual(), target(a, y).actual());
            assertNotSame(a.inputReceipt(), b.inputReceipt());
            assertEquals(4L, f.runtime.inputs.measurements().get("consumers"));
            var old = a.inputReceipt(); a.acknowledgeInputs(); a.acknowledgeInputs();
            assertEquals(2L, f.runtime.inputs.measurements().get("consumers"));
            assertEquals(FullInitializedInputs.State.SATISFIED, b.inputEvaluation().state());
            assertNotEquals(FullInitializedInputs.State.SATISFIED, a.validateInputs(a.finishing.stage(), old).state());
            b.acknowledgeInputs(); assertEquals(0L, f.runtime.inputs.measurements().get("consumers"));
            assertEquals(2L, f.runtime.measurements().get("preparationCalls"));
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void threeInputCornerAndPassengerStructureUseSharedPreparations() throws Exception {
        var f = new FullRegistrationFixture(true);
        var x = new ChunkPos(7, -3); var y = new ChunkPos(8, -3); var z = new ChunkPos(8, -2);
        try {
            // Independent expected footprints: east edge -> X/Y, south edge -> Y/Z.
            var root = tag(127.8, -40, 24000); var passengers = new ListTag(); passengers.add(tag(128.2, -32.2, 24000)); root.put("Passengers", passengers);
            saved(f, x, false, root); saved(f, y, false, tag(136, -32.2, 24000)); saved(f, z, false, tag(136, -31.8, 24000));
            f.start(x, ChunkStatus.FULL); f.start(y, ChunkStatus.FULL); f.start(z, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(x));
            // The passenger at the four-chunk corner also observes (7,-2), legitimately present
            // because the original generation cache created its entitled holder. It must prepare too.
            f.drain();
            assertBound(f, graph, Set.of(x, y, z, new ChunkPos(7, -2)));
            assertTrue(graph.savedSnapshot().entities().stream().anyMatch(fact -> fact.path().equals("root[0]/passenger[0]")));
            assertEquals(4L, f.runtime.measurements().get("preparationCalls"));
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void actualPermittedHolderDemandProducesMissingPreparationWithoutPublicFullWait() throws Exception {
        var f = new FullRegistrationFixture(true);
        var y = new ChunkPos(1, 0);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); saved(f, y, false, tag(16.2, 8, 24000));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            assertBound(f, f.runtime.graph(f.holder(ChunkPos.ZERO)), Set.of(ChunkPos.ZERO, y));
            assertBound(f, f.runtime.graph(f.holder(y)), Set.of(ChunkPos.ZERO, y));
            assertTrue(((FullHolderEvidence) (Object) f.holder(y)).phoenium$futures().get(ChunkStatus.FULL.getIndex()) != null,
                    "Only the original permitted holder demand created this subscriber slot");
            assertEquals(2L, f.runtime.measurements().get("registrations"));
            assertEquals(0, f.world.tickets.getTickets(y.pack()).size());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void savedAddEntityWriterInvalidatesAndRediscoversWithoutReconstruction() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            saved(f, ChunkPos.ZERO, false, tag(8, 8, 1)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var prior = graph.inputReceipt(); var actual = graph.prepared().orElseThrow().actual();
            f.input(ChunkPos.ZERO).addEntity(tag(15.8, 8, 24000));
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), prior).state());
            f.drain();
            assertBound(f, graph, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0)));
            assertSame(actual, graph.prepared().orElseThrow().actual()); assertNotSame(prior, graph.inputReceipt());
            assertEquals(2L, f.runtime.measurements().get("preparationCalls"));
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void exactReceiptCannotBeCopiedAcrossWorldWorkStageAttemptHolderObjectOrPurpose() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.start(new ChunkPos(2, 0), ChunkStatus.FULL); f.drain();
            var a = f.runtime.graph(f.holder(ChunkPos.ZERO)); var b = f.runtime.graph(f.holder(new ChunkPos(2, 0))); var r = a.inputReceipt();
            assertEquals(FullInitializedInputs.State.STALE, b.validateInputs(b.finishing.stage(), r).state());
            assertEquals(FullInitializedInputs.State.STALE, a.validateInputs(a.preparation.stage(), r).state());
            assertEquals(FullInitializedInputs.State.STALE, a.validateInputs(new ChunkCustodyModel.StageId(a.work, ChunkStageKind.FULL_FINISH, "FULL", 1), r).state());
            var copied = new FullInitializedInputs.Receipt(r.server(), r.world(), r.source(), r.operation(), r.plan(), r.savedRevision(), r.routes(), r.targets());
            assertEquals(FullInitializedInputs.State.STALE, a.validateInputs(a.finishing.stage(), copied).state(), "Even identical reconstructed records have no installed retention");
            var wrong = new FullInitializedInputs.Receipt(new Object(), null, b.work, b.finishing.stage(), r.plan()+1, r.savedRevision(), r.routes(), b.inputReceipt().targets());
            assertEquals(FullInitializedInputs.State.STALE, a.validateInputs(a.finishing.stage(), wrong).state());
            var t = r.targets().getFirst();
            var forgedTarget = new FullInitializedInputs.Target(t.position(), Set.of(FullInitializedInputs.Purpose.COLLISION), t.availability(), b.holder,
                    b.lifecycle, b.work, b.preparation.stage(), b.prepared().orElseThrow().actual(), t.lowerSlot(), t.publicSlot(), t.generationRetention(), t.reservation(), t.route(), t.ticketLevel());
            var forged = new FullInitializedInputs.Receipt(r.server(), r.world(), r.source(), r.operation(), r.plan(), r.savedRevision(), r.routes(), List.of(forgedTarget));
            assertEquals(FullInitializedInputs.State.STALE, a.validateInputs(a.finishing.stage(), forged).state());
            assertEquals(FullInitializedInputs.State.SATISFIED, a.validateInputs(a.finishing.stage(), r).state());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void finiteConsumerCapacityResumesOnAcknowledgementAndNeedsNoPublication() throws Exception {
        var f = new FullRegistrationFixture(true, new FullInitializedInputs.Limits(4, 16, 32, 2, 1024 * 1024));
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); saved(f, new ChunkPos(1, 0), false, tag(16.2, 8, 24000));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.start(new ChunkPos(1, 0), ChunkStatus.FULL); f.drain();
            var a = f.runtime.graph(f.holder(ChunkPos.ZERO)); var b = f.runtime.graph(f.holder(new ChunkPos(1, 0)));
            var winner = a.inputEvaluation().state() == FullInitializedInputs.State.SATISFIED ? a : b;
            var waiting = winner == a ? b : a;
            assertEquals(FullInitializedInputs.State.CAPACITY, waiting.inputEvaluation().state());
            assertEquals(2L, f.runtime.measurements().get("preparationCalls")); assertEquals(2L, f.runtime.inputs.measurements().get("consumers"));
            winner.acknowledgeInputs(); f.drain(); assertBound(f, waiting, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0)));
            assertFalse(winner.conversion().isDone()); assertFalse(waiting.conversion().isDone());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void delayedRawWriterReleaseAndDuplicateNoticesNeverBorrowAnUnpreparedInput() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); saved(f, y, false, tag(16.2, 8, 24000));
            f.delayedPosition = y; f.delayedStatus = ChunkStatus.SPAWN;
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var a = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.PENDING, a.inputEvaluation().state());
            assertNull(a.inputReceipt()); assertEquals(0L, f.runtime.inputs.measurements().get("consumers"));
            for (int i=0; i<20; i++) f.runtime.inputs.changed();
            assertEquals(1L, f.runtime.inputs.measurements().get("pendingNotifications")); f.drain();
            f.producer.complete(f.input(y)); f.runGenerationTasks(); f.drain();
            assertBound(f, a, Set.of(ChunkPos.ZERO, y));
            assertFalse(f.owner.leases().hasActiveLease());
        } finally { f.stop(); }
        f.runtime.inputs.changed(); f.drain(); assertCleanup(f);
    }

    @Test void actualHolderObjectReplacementInvalidatesBeforeAnyFinishingClaim() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var old = graph.inputReceipt();
            var replacement = new LevelChunk(f.world.level, f.world.proto, null);
            graph.holder.replaceProtoChunk(new ImposterProtoChunk(replacement, false));
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), old).state());
            f.drain(); assertEquals(FullInitializedInputs.State.STALE, graph.inputEvaluation().state());
            assertSame(replacement, ((ImposterProtoChunk) graph.holder.getChunkIfPresentUnchecked(ChunkStatus.SPAWN)).getWrapped());
            assertEquals(0L, f.runtime.inputs.measurements().get("consumers"));
            assertFalse(graph.conversion().isDone());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void topologyPublicationRebindsRoutesWithoutCreatingRegionalAuthority() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var a = f.runtime.graph(f.holder(ChunkPos.ZERO)); var old = a.inputReceipt();
            var generation = new TopologyGeneration(2);
            f.directory.publish(generation, Map.of(
                    a.lifecycle.chunkKey(), new dev.phoenium.topology.RegionRoute(f.ownerId, generation, new dev.phoenium.core.OwnerEpoch(0)),
                    new dev.phoenium.topology.RoutingKey(f.worldId, 1, 0), new dev.phoenium.topology.RegionRoute(f.otherId, generation, new dev.phoenium.core.OwnerEpoch(0))));
            assertEquals(FullInitializedInputs.State.STALE, a.validateInputs(a.finishing.stage(), old).state()); f.drain();
            assertEquals(FullInitializedInputs.State.SATISFIED, a.inputEvaluation().state());
            assertEquals(Set.of(f.ownerId, f.otherId), a.inputReceipt().targets().stream().map(t -> t.route().region()).collect(java.util.stream.Collectors.toSet()));
            assertFalse(f.owner.leases().hasActiveLease()); assertFalse(f.other.leases().hasActiveLease()); assertFalse(a.conversion().isDone());
            assertEquals(2L, f.runtime.measurements().get("preparationCalls"));
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void unboundConcreteReaderAndDimensionContextRemainExplicitWithoutRunningReaders() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            var tag = tag(15.8, 8, 24000); tag.putString("id", "minecraft:villager"); saved(f, ChunkPos.ZERO, false, tag);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.UNRESOLVED, graph.inputEvaluation().state());
            assertTrue(graph.inputEvaluation().obligation().contains("minecraft:villager")); assertEquals(0, f.world.nextId); assertTrue(f.world.inserted.isEmpty());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    static CompoundTag tag(double x, double z, int age) {
        var tag = new CompoundTag(); tag.putString("id", "minecraft:tadpole"); tag.putInt("Age", age); tag.putBoolean("AgeLocked", true);
        var pos = new ListTag(); pos.add(DoubleTag.valueOf(x)); pos.add(DoubleTag.valueOf(8)); pos.add(DoubleTag.valueOf(z)); tag.put("Pos", pos); return tag;
    }
    @Test void originalVisibleMapPublicationInvalidatesObservedAbsenceAndProducesNewInput() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
            f.world.visibleHolders.remove(y.pack()); f.publishVisible(); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var old = graph.inputReceipt();
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state());
            assertEquals(FullInitializedInputs.Availability.ABSENT, target(graph, y).availability()); assertNull(target(graph, y).actual());
            f.world.visibleHolders.put(y.pack(), f.holder(y)); f.publishVisible();
            assertEquals(1L, f.runtime.inputs.measurements().get("pendingNotifications"));
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), old).state()); f.drain();
            assertBound(f, graph, Set.of(ChunkPos.ZERO, y)); assertEquals(2L, f.runtime.measurements().get("preparationCalls"));
        } finally { f.stop(); }
        assertEquals(0, ((FullInputView) f.fixtureMap).phoenium$inputChanges().retainedListeners()); assertCleanup(f);
    }

    @Test void inlinePreparationNotificationCannotExposeAnUnboundFinishingInputOrResurrectClosedUse() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var reentered = graph.preparation.outcome().thenRun(() -> {
                assertTrue(graph.prepared().isPresent()); assertFalse(f.owner.leases().hasActiveLease());
                f.runtime.inputs.changed(); while (f.runtime.pumpOne()) { }
                assertNull(graph.inputReceipt(), "Source binding is installed before any input receipt notification");
            });
            f.drain(); reentered.join(); assertBound(f, graph, Set.of(ChunkPos.ZERO));
            var receipt = graph.inputReceipt();
            for (int i = 0; i < 10; i++) f.runtime.inputs.changed(); f.drain(); assertSame(receipt, graph.inputReceipt());
            graph.acknowledgeInputs(); for (int i=0; i<10; i++) f.runtime.inputs.changed(); f.drain();
            assertNull(graph.inputReceipt()); assertEquals(0L, f.runtime.inputs.measurements().get("plans"));
            assertEquals(FullInitializedInputs.State.TERMINAL, graph.validateInputs(graph.finishing.stage(), receipt).state());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void planAndPayloadCapacityDoesNotReserveResourcesNeededForTargetPreparation() throws Exception {
        var f = new FullRegistrationFixture(true, new FullInitializedInputs.Limits(1, 16, 16, 16, FullSavedInput.MAX_FACT_BYTES));
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); saved(f, new ChunkPos(1, 0), false, tag(16.2, 8, 24000));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var a = f.runtime.graph(f.holder(ChunkPos.ZERO)); var b = f.runtime.graph(f.holder(new ChunkPos(1, 0)));
            assertBound(f, a, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0)));
            assertEquals(FullInitializedInputs.State.CAPACITY, b.inputEvaluation().state());
            assertNull(b.savedSnapshot(), "no copied fact payload held before its capacity admission");
            assertEquals(2L, f.runtime.measurements().get("preparationCalls"));
            a.acknowledgeInputs(); f.drain(); assertBound(f, b, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0)));
            assertEquals((long) FullSavedInput.MAX_FACT_BYTES, f.runtime.inputs.measurements().get("factBytes"));
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void realSavedWriterIsExcludedBeforeMutationDuringTheObservationTurn() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var input = ((FullSavedInput.Access) f.input(ChunkPos.ZERO)).phoenium$savedInput(); var receipt = graph.inputReceipt();
            int count = f.input(ChunkPos.ZERO).getEntities().size();
            input.beforeWrite();
            try {
                assertEquals(FullInitializedInputs.State.PENDING, graph.validateInputs(graph.finishing.stage(), receipt).state());
                assertThrows(ChunkProtocolException.class, () -> f.input(ChunkPos.ZERO).addEntity(tag(15.8, 8, 24000)));
            }
            finally { input.endWrite(); }
            assertEquals(count, f.input(ChunkPos.ZERO).getEntities().size()); assertSame(receipt, graph.inputReceipt());
            f.input(ChunkPos.ZERO).addEntity(tag(15.8, 8, 24000)); f.drain();
            assertBound(f, graph, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0)));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void nonAirShapePotentialRemainsMutationCapableAndNeverCreatesBlockEntitiesForDiscovery() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000));
            var state = net.minecraft.world.level.block.Blocks.SHULKER_BOX.defaultBlockState();
            f.input(ChunkPos.ZERO).getSection(0).setBlockState(15, 8, 8, state);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertBound(f, graph, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0)));
            assertTrue(target(graph, ChunkPos.ZERO).purposes().contains(FullInitializedInputs.Purpose.DYNAMIC_SHAPE_MUTATION));
            assertTrue(graph.prepared().orElseThrow().actual().getBlockEntities().isEmpty());
            assertTrue(graph.missingFinishingBindings().contains("W named world turns"));
            assertTrue(graph.missingFinishingBindings().contains("private query operation bindings and regional enclosure"));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void oversizedDiscoveryIsAnUnresolvedBoundAndDoesNotFabricateEmptySuccess() throws Exception {
        var f = new FullRegistrationFixture(true, new FullInitializedInputs.Limits(2, 1, 8, 8, 1024 * 1024));
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.UNRESOLVED, graph.inputEvaluation().state()); assertNull(graph.inputReceipt());
            assertEquals(0L, f.runtime.inputs.measurements().get("consumers")); assertFalse(graph.conversion().isDone());
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void contextDependentDimensionsHaveAnExactUnmetRequirement() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            var tag = tag(15.8, 8, 24000); tag.put("attributes", new ListTag()); saved(f, ChunkPos.ZERO, false, tag);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.UNRESOLVED, graph.inputEvaluation().state());
            assertTrue(graph.inputEvaluation().obligation().contains("dimension context")); assertNull(graph.inputReceipt());
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void originalCompoundPositionAndPassengerWritersInvalidateProtoAndRetainedReload(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            var root = tag(15.8, 8, 1); var passengers = new ListTag(); root.put("Passengers", passengers);
            saved(f, ChunkPos.ZERO, reload, root); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var actual = graph.prepared().orElseThrow().actual();
            var first = graph.inputReceipt(); root.putInt("Age", 24000);
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), first).state());
            f.drain(); assertBound(f, graph, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0)));
            var second = graph.inputReceipt(); root.getListOrEmpty("Pos").set(2, DoubleTag.valueOf(15.8));
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), second).state());
            f.drain(); assertBound(f, graph, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0), new ChunkPos(0, 1), new ChunkPos(1, 1)));
            passengers.add(tag(48.2, 8, 24000)); f.drain();
            assertBound(f, graph, Set.of(ChunkPos.ZERO, new ChunkPos(1, 0), new ChunkPos(0, 1), new ChunkPos(1, 1), new ChunkPos(2, 0), new ChunkPos(3, 0)));
            assertSame(actual, graph.prepared().orElseThrow().actual()); assertEquals(6L, f.runtime.measurements().get("preparationCalls"));
            var third = graph.inputReceipt(); root.entrySet().stream().filter(e -> e.getKey().equals("Age")).findFirst().orElseThrow().setValue(IntTag.valueOf(1));
            passengers.clear(); f.drain(); assertBound(f, graph, Set.of(ChunkPos.ZERO));
            assertNotSame(third, graph.inputReceipt()); assertEquals(6L, f.runtime.measurements().get("preparationCalls"));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void actualPendingTopologyHasACompletionProducerAndRebindsTheSamePreparedObjects() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var actual = graph.prepared().orElseThrow().actual();
            var runtimes = Map.of(f.ownerId, f.owner);
            var topology = new dev.phoenium.topology.TopologyCoordinator(f.directory, runtimes,
                    new dev.phoenium.scheduler.OwnerGateway(f.directory, runtimes,
                            new dev.phoenium.scheduler.RegionScheduler(runtimes, f.directory, f.barrier)));
            topology.request(new dev.phoenium.topology.TopologyCoordinator.Transition(Set.of(f.ownerId), Map.of(
                    graph.lifecycle.chunkKey(), f.ownerId, new dev.phoenium.topology.RoutingKey(f.worldId, 1, 0), f.ownerId)));
            f.drain(); assertEquals(FullInitializedInputs.State.PENDING, graph.inputEvaluation().state());
            assertTrue(graph.inputTargets().stream().allMatch(t -> t.availability() == FullInitializedInputs.Availability.TOPOLOGY_PENDING));
            assertFalse(f.owner.leases().hasActiveLease()); assertTrue(topology.advance()); f.drain();
            assertBound(f, graph, Set.of(ChunkPos.ZERO, y)); assertSame(actual, graph.prepared().orElseThrow().actual());
            assertEquals(2L, f.runtime.measurements().get("preparationCalls"));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void sharedMutableSavedPositionCannotBeReadUnderAnotherInputsCustody() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            var first = tag(8, 8, 1); var second = tag(24, 8, 1);
            second.put("Pos", first.getListOrEmpty("Pos"));
            saved(f, ChunkPos.ZERO, false, first); saved(f, y, false, second);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var a = f.runtime.graph(f.holder(ChunkPos.ZERO)); assertBound(f, a, Set.of(ChunkPos.ZERO));
            f.start(y, ChunkStatus.FULL); f.drain(); var b = f.runtime.graph(f.holder(y));
            assertTrue(b.prepared().isPresent());
            assertEquals(FullInitializedInputs.State.UNRESOLVED, b.inputEvaluation().state());
            assertTrue(b.inputEvaluation().obligation().contains("another input custodian"));
            assertNull(b.inputReceipt()); assertBound(f, a, Set.of(ChunkPos.ZERO));
            assertFalse(b.conversion().isDone()); assertEquals(0, f.world.nextId);
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void targetPreparationFailureSuppliesNoReceiptAndIsNotAPendingPublicFullDependency() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        var delayed = net.minecraft.util.thread.BlockableEventLoop.class.getDeclaredField("delayedCrash"); delayed.setAccessible(true);
        Object previous = delayed.get(null);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000));
            f.holder(y).afterReplace = () -> { throw new IllegalStateException("controlled original replacement failure"); };
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var target = f.runtime.graph(f.holder(y));
            assertTrue(target.prepared().isEmpty());
            assertInstanceOf(ChunkStageOutcome.VanillaFailure.class, target.preparation.outcome().join());
            assertEquals(FullInitializedInputs.State.TERMINAL, graph.inputEvaluation().state()); assertNull(graph.inputReceipt());
            assertEquals(0L, f.runtime.inputs.measurements().get("consumers")); assertFalse(graph.conversion().isDone());
            assertEquals(2L, f.runtime.measurements().get("preparationCalls"));
        } finally { f.stop(); delayed.set(null, previous); }
        assertCleanup(f);
    }

    @Test void replacementKeepsOldPreparedConsumerUntilAcknowledgementAndDoesNotRemoveNewHolder() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0); AutoCloseable retained = null;
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var a = f.runtime.graph(f.holder(ChunkPos.ZERO)); var old = f.runtime.graph(f.holder(y)); var oldActual = old.prepared().orElseThrow().actual();
            retained = old.retainPrepared(); f.runtime.retire(old.holder);
            f.holders.remove(y.pack()); f.inputs.remove(y.pack()); saved(f, y, false, tag(16.2, 8, 24000));
            f.start(y, ChunkStatus.FULL); f.runGenerationTasks(); f.drain();
            var replacement = f.runtime.graph(f.holder(y)); assertNotEquals(old.work, replacement.work);
            assertEquals(3L, f.runtime.measurements().get("graphs")); assertSame(oldActual, old.prepared().orElseThrow().actual());
            assertBound(f, a, Set.of(ChunkPos.ZERO, y)); assertSame(replacement.prepared().orElseThrow().actual(), target(a, y).actual());
            retained.close(); retained.close(); retained = null;
            assertEquals(0, old.preparedConsumers());
            assertTrue(((FullHolderEvidence) old.holder).phoenium$generationRefs().get() > 0,
                    "X's original generation cache still pins the old Y until its own real task releases");
            assertEquals(3L, f.runtime.measurements().get("graphs")); assertSame(replacement.holder, ((FullInputView) f.fixtureMap).phoenium$visibleInput(y.pack()));
            assertFalse(replacement.conversion().isDone());
        } finally { f.stop(); if (retained != null) retained.close(); }
        assertCleanup(f);
    }
    static void saved(FullRegistrationFixture f, ChunkPos pos, boolean reload, CompoundTag tag) throws Exception {
        var proto = f.input(pos); proto.getEntities().clear(); proto.addEntity(tag);
        if (reload) {
            var factory = SerializableChunkData.class.getDeclaredMethod("postLoadChunk", net.minecraft.server.level.ServerLevel.class, List.class, List.class); factory.setAccessible(true);
            var callback = (LevelChunk.PostLoadProcessor) factory.invoke(null, f.world.level, proto.getEntities(), List.of());
            f.inputs.put(pos.pack(), new ImposterProtoChunk(new LevelChunk(f.world.level, proto, callback), false));
        }
    }
    static FullInitializedInputs.Target target(FullRegistrationRuntime.Graph graph, ChunkPos pos) { return graph.inputReceipt().targets().stream().filter(t -> t.position().equals(pos)).findFirst().orElseThrow(); }
    static void assertBound(FullRegistrationFixture f, FullRegistrationRuntime.Graph graph, Set<ChunkPos> expected) throws Exception {
        assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
        assertEquals(expected, graph.inputReceipt().targets().stream().map(FullInitializedInputs.Target::position).collect(java.util.stream.Collectors.toSet()));
        assertNotNull(graph.lastInputEvaluation()); assertEquals(FullInitializedInputs.State.SATISFIED, graph.lastInputEvaluation().state());
        for (var t : graph.inputReceipt().targets()) {
            assertEquals(FullInitializedInputs.Availability.PREPARED, t.availability());
            assertSame(f.runtime.graph(t.holder()).prepared().orElseThrow().actual(), t.actual());
            if (t.purposes().contains(FullInitializedInputs.Purpose.COLLISION)) assertTrue(t.purposes().contains(FullInitializedInputs.Purpose.DYNAMIC_SHAPE_MUTATION));
            var slot = ((FullHolderEvidence) t.holder()).phoenium$futures().get(ChunkStatus.FULL.getIndex()); assertTrue(slot == null || !slot.isDone());
        }
        assertEquals(ChunkCustodyModel.StageState.QUEUED, graph.finishing.state().orElseThrow());
        assertFalse(graph.conversion().isDone()); assertFalse(graph.returned().isDone()); assertFalse(graph.adjacent().isDone());
        assertTrue(f.world.inserted.isEmpty()); assertEquals(0, f.world.nextId); assertEquals(0, f.world.sounds); assertFalse(f.owner.leases().hasActiveLease());
        assertTrue(Arrays.stream((ChunkAccess[]) FullDecisionWitnessSupport.field(f.world.cache,
                net.minecraft.server.level.ServerChunkCache.class, "lastChunk")).allMatch(Objects::isNull));
    }
    static void assertCleanup(FullRegistrationFixture f) {
        assertEquals(0L, f.runtime.measurements().get("graphs")); assertEquals(0L, f.runtime.measurements().get("generationHolders"));
        for (String name : List.of("plans", "edges", "consumers", "factBytes", "pendingNotifications", "requestedPreparations")) assertEquals(0L, f.runtime.inputs.measurements().get(name), name);
        assertEquals(0, f.services.custody().retainedEntries());
    }
}
