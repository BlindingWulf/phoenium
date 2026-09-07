package dev.phoenium.chunk;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.server.level.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original baseline target producer and separately selected source, with controlled world endpoints. */
final class FullPublishedInputBindingTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void originalPublishedNeighborFeedsActualFinishingEvaluatorWithoutSourceFinishing(boolean reload, boolean inline) throws Exception {
        var f = new FullRegistrationFixture(inline); var y = new ChunkPos(1, 0);
        try {
            baselineInput(f, y, reload); f.startBaseline(y); f.drain(); f.runGenerationTasks();
            var actual = (LevelChunk) f.holder(y).getChunkIfPresent(ChunkStatus.FULL);
            assertNotNull(actual, "Y must reach its original successful public slot");
            var publication = f.runtime.published.observe(f.holder(y));
            assertNotNull(publication); assertSame(actual, publication.originalValue());
            assertSame(publication.result(), publication.slot().getNow(null));
            assertSame(actual, publication.result().orElse(null));
            assertEquals(reload ? FullPublishedInputs.Origin.ORIGINAL_BASELINE_IMPOSTER : FullPublishedInputs.Origin.ORIGINAL_BASELINE_PROTO, publication.origin());
            assertEquals(0L, f.runtime.published.measurements().get("transientExecutions"));
            assertTrue(f.services.custody().workState(publication.work()).isEmpty(), "producer execution settled without resurrecting its WorkId");
            assertTrue(f.services.lifecycles().isCurrent(publication.lifecycle()));
            assertEquals(0L, f.runtime.measurements().get("graphs"));
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var source = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, source.inputEvaluation().state(), source.inputEvaluation().obligation());
            assertSame(actual, target(source, y).actual());
            assertSame(publication, target(source, y).publication());
            assertEquals(publication.work(), target(source, y).work());
            assertEquals(FullInitializedInputs.Availability.PUBLISHED, target(source, y).availability());
            assertEquals(FullInitializedInputs.State.SATISFIED, source.lastInputEvaluation().state());
            assertEquals(ChunkCustodyModel.StageState.QUEUED, source.finishing.state().orElseThrow());
            assertFalse(source.conversion().isDone()); assertFalse(source.adjacent().isDone());
            assertEquals(true, FullDecisionWitnessSupport.field(actual, LevelChunk.class, "loaded"));
            assertEquals(false, FullDecisionWitnessSupport.field(source.prepared().orElseThrow().actual(), LevelChunk.class, "loaded"));
            assertEquals(1L, f.runtime.published.measurements().get("publications"));
            assertEquals(1L, f.runtime.measurements().get("preparationCalls"));
            source.acknowledgeInputs();
            assertSame(actual, f.holder(y).getChunkIfPresent(ChunkStatus.FULL));
            assertSame(publication, f.runtime.published.observe(f.holder(y)));
            assertEquals(0L, f.runtime.published.measurements().get("uses"));
        } finally { f.stop(); }
        assertCleanup(f);
        assertEquals(0L, f.runtime.published.measurements().get("records"));
    }

    static void baselineInput(FullRegistrationFixture f, ChunkPos y, boolean reload) throws Exception {
        var proto = f.input(y); proto.getEntities().clear();
        if (reload) {
            var factory = net.minecraft.world.level.chunk.storage.SerializableChunkData.class.getDeclaredMethod("postLoadChunk", ServerLevel.class, List.class, List.class);
            factory.setAccessible(true);
            var processor = (LevelChunk.PostLoadProcessor) factory.invoke(null, f.world.level, proto.getEntities(), List.of());
            f.inputs.put(y.pack(), new ImposterProtoChunk(new LevelChunk(f.world.level, proto, processor), false));
        }
    }
    static FullRegistrationRuntime.Graph source(FullRegistrationFixture f, ChunkPos pos, double entityX) throws Exception {
        saved(f, pos, false, tag(entityX, 8, 24000)); f.start(pos, ChunkStatus.FULL); f.drain(); return f.runtime.graph(f.holder(pos));
    }
    @Test void inlinePublicSubscriberObservesInstalledLineageBeforeOriginalHandlerReturns() throws Exception {
        var f = new FullRegistrationFixture(false); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y);
            var slot = f.holder(y).scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap);
            assertFalse(slot.isDone());
            var observed = new ArrayList<FullPublishedInputs.Evidence>();
            slot.thenRun(() -> {
                assertFalse(f.services.custody().gate().isHeldByCurrentThread());
                var evidence = f.runtime.published.observe(f.holder(y)); assertNotNull(evidence);
                observed.add(evidence);
                try { var use = f.runtime.published.retain(evidence); assertNotNull(use); use.close(); } catch (Exception e) { throw new AssertionError(e); }
            });
            f.drain(); f.runGenerationTasks();
            assertEquals(1, observed.size()); assertSame(observed.getFirst(), f.runtime.published.observe(f.holder(y)));
            for (int i = 0; i < 20; i++) FullPublishedInputs.completed(f.holder(y), ChunkStatus.FULL, true);
            assertEquals(1L, f.runtime.published.measurements().get("publications"));
            assertEquals(0L, f.runtime.published.measurements().get("uses"));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void targetPublicationWhileDiscoveryIsSubscribedReplacesPendingObservation() throws Exception {
        var f = new FullRegistrationFixture(false); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); Runnable targetDelivery = f.registrations.removeFirst();
            var source = source(f, ChunkPos.ZERO, 15.8);
            assertEquals(FullInitializedInputs.State.PENDING, source.inputEvaluation().state());
            assertNull(source.inputReceipt()); assertEquals(0L, f.runtime.inputs.measurements().get("consumers"));
            targetDelivery.run(); f.runGenerationTasks(); f.drain();
            assertEquals(FullInitializedInputs.State.SATISFIED, source.lastInputEvaluation().state());
            assertSame(f.holder(y).getChunkIfPresent(ChunkStatus.FULL), target(source, y).actual());
            assertEquals(1L, f.runtime.published.measurements().get("uses"));
            assertEquals(1L, f.runtime.measurements().get("registrations"));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void independentConsumersAndSourceCancellationPreserveResidentTargetAndUnrelatedDemand() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0); var z = new ChunkPos(2, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var evidence = f.runtime.published.observe(f.holder(y));
            var unrelated = f.services.works().beginOrResolveSharedWork(evidence.lifecycle(), new ChunkWorkRegistry.WorkReservation("unrelated", "resident", 0));
            var demand = f.services.works().addDemand(unrelated);
            var a = source(f, ChunkPos.ZERO, 15.8); var b = source(f, z, 32.2);
            assertEquals(2L, f.runtime.published.measurements().get("uses"));
            var old = a.inputReceipt(); a.acknowledgeInputs(); a.acknowledgeInputs();
            assertEquals(1L, f.runtime.published.measurements().get("uses"));
            assertEquals(FullInitializedInputs.State.SATISFIED, b.inputEvaluation().state());
            assertEquals(FullInitializedInputs.State.TERMINAL, a.validateInputs(a.finishing.stage(), old).state());
            f.services.works().retireWork(b.work, true); f.drain();
            assertEquals(0L, f.runtime.published.measurements().get("uses"));
            assertTrue(f.services.custody().demandActive(demand)); assertTrue(f.services.works().isCurrentWork(unrelated));
            assertTrue(f.services.lifecycles().isCurrent(evidence.lifecycle()));
            assertSame(evidence, f.runtime.published.observe(f.holder(y)));
            assertSame(evidence.originalValue(), f.holder(y).getChunkIfPresent(ChunkStatus.FULL));
            f.services.works().removeDemand(demand); f.services.settleFinishedExecution(unrelated, f.holder(y));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void mixedPreparedPublishedAndAbsentWindowPreservesMutationObligations() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0); var z = new ChunkPos(0, 1); var absent = new ChunkPos(1, 1);
        try {
            f.startBaseline(y); f.runGenerationTasks(); saved(f, ChunkPos.ZERO, false, tag(15.8, 15.8, 24000));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.world.visibleHolders.remove(absent.pack()); f.publishVisible(); f.drain();
            var source = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, source.lastInputEvaluation().state());
            assertEquals(FullInitializedInputs.Availability.PUBLISHED, target(source, y).availability());
            assertEquals(FullInitializedInputs.Availability.PREPARED, target(source, z).availability());
            assertEquals(FullInitializedInputs.Availability.ABSENT, target(source, absent).availability());
            for (var target : source.inputTargets()) if (target.purposes().contains(FullInitializedInputs.Purpose.COLLISION))
                assertTrue(target.purposes().contains(FullInitializedInputs.Purpose.DYNAMIC_SHAPE_MUTATION));
            assertEquals(ChunkCustodyModel.StageState.QUEUED, source.finishing.state().orElseThrow());
            assertFalse(source.conversion().isDone());
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void realSlotReplacementAndTicketEligibilityRejectNewUseButPreserveOldAcknowledgement() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var evidence = f.runtime.published.observe(f.holder(y));
            var use = f.runtime.published.retain(evidence); assertNotNull(use);
            f.holder(y).eligibility(34, f.fixtureMap); assertNull(f.runtime.published.retain(evidence));
            f.holder(y).eligibility(33, f.fixtureMap); assertNotNull(f.runtime.published.observe(f.holder(y)));
            var other = new LevelChunk(f.world.level, f.input(y), null);
            f.holder(y).replaceProtoChunk(new ImposterProtoChunk(other, false));
            assertNull(f.runtime.published.observe(f.holder(y))); assertNull(f.runtime.published.retain(evidence));
            use.close(); use.close(); assertEquals(0L, f.runtime.published.measurements().get("uses"));
            assertSame(other, ((ImposterProtoChunk) f.holder(y).getChunkIfPresentUnchecked(ChunkStatus.SPAWN)).getWrapped());
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void replacementDuringPinInstallationCannotExposeSuccessOrRemoveReplacement() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var holder = f.holder(y); var evidence = f.runtime.published.observe(holder);
            assertEquals(0, ((FullHolderEvidence) (Object) holder).phoenium$generationRefs().get());
            var replacement = new FullRegistrationFixture.Holder(y);
            holder.afterSave = () -> { f.holders.put(y.pack(), replacement); f.world.visibleHolders.put(y.pack(), replacement); f.publishVisible(); };
            assertNull(f.runtime.published.retain(evidence));
            assertEquals(0L, f.runtime.published.measurements().get("uses"));
            assertSame(replacement, ((FullInputView) f.fixtureMap).phoenium$visibleInput(y.pack()));
            assertEquals(0, ((FullHolderEvidence) (Object) holder).phoenium$generationRefs().get());
            assertEquals(0L, f.runtime.published.measurements().get("records"));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void acknowledgementReentryInstallsANewerUseAndDuplicateOldCloseCannotRevokeIt() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var evidence = f.runtime.published.observe(f.holder(y));
            var old = f.runtime.published.retain(evidence); var newer = new ArrayList<AutoCloseable>();
            var callback = ((FullHolderEvidence) (Object) f.holder(y)).phoenium$generationSaveSync().thenRun(() -> {
                assertFalse(f.services.custody().gate().isHeldByCurrentThread()); newer.add(f.runtime.published.retain(evidence));
            });
            old.close(); assertTrue(callback.isDone()); assertFalse(callback.isCompletedExceptionally());
            old.close(); assertEquals(1L, f.runtime.published.measurements().get("uses"));
            newer.getFirst().close(); assertEquals(0L, f.runtime.published.measurements().get("uses"));
            assertSame(evidence, f.runtime.published.observe(f.holder(y)));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void capacityReleaseAndRepeatedPlanAcknowledgementsKeepOnlyResidentMetadata() throws Exception {
        var f = new FullRegistrationFixture(true, new FullInitializedInputs.Limits(4, 16, 32, 2, 1024 * 1024)); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var a = source(f, ChunkPos.ZERO, 15.8); var b = source(f, new ChunkPos(2, 0), 32.2);
            assertEquals(FullInitializedInputs.State.CAPACITY, b.inputEvaluation().state()); assertFalse(b.conversion().isDone());
            a.acknowledgeInputs(); f.drain(); assertEquals(FullInitializedInputs.State.SATISFIED, b.inputEvaluation().state());
            for (int i = 0; i < 34; i++) {
                f.input(new ChunkPos(2, 0)).getEntities().getFirst().putInt("Age", 1); f.drain();
                assertEquals(0L, f.runtime.published.measurements().get("uses"));
                f.input(new ChunkPos(2, 0)).getEntities().getFirst().putInt("Age", 24000); f.drain();
                assertEquals(FullInitializedInputs.State.SATISFIED, b.inputEvaluation().state());
                assertEquals(1L, f.runtime.published.measurements().get("uses"));
                assertEquals(1L, f.runtime.inputs.measurements().get("plans"));
                assertEquals(2L, f.runtime.inputs.measurements().get("consumers"));
            }
            b.acknowledgeInputs(); f.drain();
            assertEquals(3L, f.runtime.published.measurements().get("records"), "one published origin plus two active admitted reservations");
            assertEquals(2L, f.runtime.published.measurements().get("transientExecutions"));
            assertEquals(0L, f.runtime.published.measurements().get("uses"));
            assertEquals(0L, f.runtime.inputs.measurements().get("plans"));
        } finally { f.stop(); }
        assertCleanup(f); assertEquals(0L, f.runtime.published.measurements().get("records"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"incomplete", "exceptional", "cancelled", "unloaded"})
    void actualOriginalProducerCannotSupplyEvidenceWithoutSuccessfulPublication(String disposition) throws Exception {
        var f = new FullRegistrationFixture(false); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); Runnable delivery = f.registrations.removeFirst();
            var holder = f.holder(y); var hooks = (FullHolderEvidence) (Object) holder;
            var original = hooks.phoenium$publication().returned;
            assertNull(f.runtime.published.observe(holder));
            if (disposition.equals("exceptional")) original.completeExceptionally(new IllegalStateException("controlled original result failure"));
            if (disposition.equals("cancelled")) original.cancel(false);
            if (disposition.equals("unloaded")) {
                var slot = holder.scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap);
                holder.eligibility(34, f.fixtureMap);
                assertSame(GenerationChunkHolder.UNLOADED_CHUNK, slot.getNow(null));
                assertNull(hooks.phoenium$futures().get(ChunkStatus.FULL.getIndex()));
            }
            assertNull(f.runtime.published.observe(holder));
            assertEquals(0L, f.runtime.published.measurements().get("publications"));
            // Actual queued producer is eventually acknowledged; it cannot turn an obsolete outcome into new evidence.
            if (!disposition.equals("incomplete")) {
                delivery.run(); f.runGenerationTasks();
                assertNull(f.runtime.published.observe(holder));
            } else { delivery.run(); f.runGenerationTasks(); assertNotNull(f.runtime.published.observe(holder)); }
        } finally { f.stop(); }
        assertCleanup(f); assertEquals(0L, f.runtime.published.measurements().get("records"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"wrong-status", "exceptional-slot", "cancelled-slot", "copied-wrapper", "copied-slot"})
    void alteredSlotsAndCopiedResultWrappersHaveNoPublicationAuthority(String tamper) throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var evidence = f.runtime.published.observe(f.holder(y));
            var graph = source(f, ChunkPos.ZERO, 15.8); var prior = graph.inputReceipt();
            var hooks = (FullHolderEvidence) (Object) f.holder(y);
            // Adversarial corruption only: no successful fixture uses these synthetic values as provenance.
            CompletableFuture<ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> altered = new CompletableFuture<>();
            switch (tamper) {
                case "wrong-status" -> altered.complete(ChunkResult.of(f.input(y)));
                case "exceptional-slot" -> altered.completeExceptionally(new IllegalStateException("altered slot"));
                case "cancelled-slot" -> altered.cancel(false);
                case "copied-wrapper" -> altered.complete(ChunkResult.of(evidence.originalValue()));
                case "copied-slot" -> altered.complete(evidence.result());
            }
            hooks.phoenium$futures().set(ChunkStatus.FULL.getIndex(), altered);
            assertNull(f.runtime.published.observe(f.holder(y)));
            assertNull(f.runtime.published.retain(evidence));
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), prior).state());
            graph.acknowledgeInputs(); assertSame(altered, hooks.phoenium$futures().get(ChunkStatus.FULL.getIndex()));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void lateOriginalH1PublicationCannotBindToH2AndOldAcknowledgementLeavesH2Untouched() throws Exception {
        var f = new FullRegistrationFixture(false); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); Runnable oldDelivery = f.registrations.removeFirst(); var oldHolder = f.holder(y);
            var replacement = new FullRegistrationFixture.Holder(y);
            f.holders.put(y.pack(), replacement); f.world.visibleHolders.put(y.pack(), replacement); f.publishVisible();
            f.startBaseline(y); f.drain(); f.runGenerationTasks();
            var current = f.runtime.published.observe(replacement); assertNotNull(current);
            oldDelivery.run(); f.runGenerationTasks(); f.drain();
            assertNull(f.runtime.published.observe(oldHolder)); assertSame(current, f.runtime.published.observe(replacement));
            var graph = source(f, ChunkPos.ZERO, 15.8); assertSame(current, target(graph, y).publication());
            graph.acknowledgeInputs(); assertSame(current, f.runtime.published.observe(replacement));
            assertEquals(2L, f.runtime.published.measurements().get("records"), "current publication and the active source reservation");
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void sourceAcknowledgementDuringPublicationNotificationCannotResurrectAReceipt() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks();
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, 24000)); f.start(ChunkPos.ZERO, ChunkStatus.FULL);
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            // Original generation pins may already be live: use publication notification reentry at release instead.
            AutoCloseable notification = f.runtime.published.changes().subscribe(graph::acknowledgeInputs);
            f.runtime.published.changes().published(); f.drain(); notification.close();
            assertNull(graph.inputReceipt()); assertEquals(FullInitializedInputs.State.TERMINAL, graph.inputEvaluation().state());
            assertEquals(0L, f.runtime.published.measurements().get("uses"));
            assertEquals(0L, f.runtime.inputs.measurements().get("consumers"));
            assertNotNull(f.runtime.published.observe(f.holder(y)));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void oldOperationReceiptAcknowledgementCannotRevokeNewPlanAndCopiedPublicationCannotBeRetained() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var graph = source(f, ChunkPos.ZERO, 15.8);
            var old = graph.inputReceipt(); var e = target(graph, y).publication();
            var copied = new FullPublishedInputs.Evidence(e.holder(), e.lifecycle(), e.work(), e.step(), e.reservation(), e.origin(),
                    e.slot(), e.result(), e.originalValue(), e.actual(), e.lowerSlot(), e.revision());
            assertNull(f.runtime.published.retain(copied));
            f.input(ChunkPos.ZERO).getEntities().getFirst().putInt("Age", 24001); f.drain();
            var current = graph.inputReceipt(); assertNotSame(old, current);
            graph.acknowledgeInputs(old); graph.acknowledgeInputs(old);
            assertSame(current, graph.inputReceipt()); assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state());
            assertEquals(1L, f.runtime.published.measurements().get("uses"));
            graph.acknowledgeInputs(current); assertEquals(0L, f.runtime.published.measurements().get("uses"));
            assertSame(e, f.runtime.published.observe(f.holder(y)));
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void originalUnloadWaitsForRetainedUseThenReclaimsTheActualHolder() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var holder = f.holder(y); var evidence = f.runtime.published.observe(holder);
            var use = f.runtime.published.retain(evidence); assertNotNull(use);
            var queue = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
            var pending = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkHolder>(); pending.put(y.pack(), holder);
            FullDecisionWitnessSupport.set(f.fixtureMap, ChunkMap.class, "pendingUnloads", pending);
            FullDecisionWitnessSupport.set(f.fixtureMap, ChunkMap.class, "unloadQueue", queue);
            FullDecisionWitnessSupport.set(f.fixtureMap, ChunkMap.class, "level", f.world.level);
            var poi = FullDecisionWitnessSupport.stub(net.minecraft.world.entity.ai.village.poi.PoiManager.class, List.of(
                    new FullDecisionWitnessSupport.Answer(net.minecraft.world.entity.ai.village.poi.PoiManager.class.getMethod("flush", ChunkPos.class), args -> null)));
            FullDecisionWitnessSupport.set(f.fixtureMap, ChunkMap.class, "poiManager", poi);
            var light = FullDecisionWitnessSupport.stub(ThreadedLevelLightEngine.class, List.of(
                    new FullDecisionWitnessSupport.Answer(ThreadedLevelLightEngine.class.getDeclaredMethod("updateChunkStatus", ChunkPos.class), args -> null),
                    new FullDecisionWitnessSupport.Answer(ThreadedLevelLightEngine.class.getMethod("tryScheduleUpdate"), args -> null)));
            FullDecisionWitnessSupport.set(f.fixtureMap, ChunkMap.class, "lightEngine", light);
            FullDecisionWitnessSupport.set(f.fixtureMap, ChunkMap.class, "nextChunkSaveTime", new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap());
            evidence.actual().tryMarkSaved();
            f.world.visibleHolders.remove(y.pack()); f.publishVisible(); holder.eligibility(34, f.fixtureMap);
            assertNull(f.runtime.published.retain(evidence));
            var unload = ChunkMap.class.getDeclaredMethod("scheduleUnload", long.class, ChunkHolder.class); unload.setAccessible(true); unload.invoke(f.fixtureMap, y.pack(), holder);
            assertTrue(queue.isEmpty(), "actual save-sync postpones original pending-unload callback");
            assertEquals(1L, f.runtime.published.measurements().get("uses"));
            use.close(); assertEquals(1, queue.size()); queue.remove().run();
            assertFalse(pending.containsKey(y.pack()));
            assertTrue(f.world.events.contains("controlled-world-unload"));
            assertEquals(false, FullDecisionWitnessSupport.field(evidence.actual(), LevelChunk.class, "loaded"));
            assertEquals(0L, f.runtime.published.measurements().get("records"));
            assertEquals(0, f.services.custody().retainedEntries());
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void trueTargetRetirementKeepsOldEvidenceUntilItsOwnUseAcknowledges() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var evidence = f.runtime.published.observe(f.holder(y));
            var use = f.runtime.published.retain(evidence);
            f.runtime.retire(f.holder(y));
            assertEquals(ChunkCustodyModel.LifecycleState.RETIRED, f.services.custody().lifecycleState(evidence.lifecycle()).orElseThrow());
            assertNull(f.runtime.published.retain(evidence));
            assertEquals(1L, f.runtime.published.measurements().get("records"));
            assertThrows(ChunkProtocolException.class, () -> f.services.releaseRetiredLifecycle(evidence.lifecycle()));
            assertSame(evidence.originalValue(), evidence.slot().getNow(null).orElse(null));
            use.close(); use.close(); assertEquals(0L, f.runtime.published.measurements().get("records"));
            assertEquals(0, f.services.custody().retainedEntries()); assertEquals(0, f.services.residentHolderEntries());
        } finally { f.stop(); }
        assertCleanup(f);
    }
    @Test void operationConsumerExpansionAcknowledgesEveryPreparedAndPublishedUse() throws Exception {
        var f = new FullRegistrationFixture(true, new FullInitializedInputs.Limits(4, 16, 32, 1, 1024 * 1024)); var y = new ChunkPos(1, 0);
        try {
            f.startBaseline(y); f.runGenerationTasks(); var graph = source(f, ChunkPos.ZERO, 15.8);
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state());
            assertEquals(2L, f.runtime.inputs.measurements().get("consumers"));
            assertEquals(1L, f.runtime.inputs.measurements().get("expandedOperations"));
            assertEquals(1L, f.runtime.published.measurements().get("uses"));
            assertFalse(graph.conversion().isDone()); assertTrue(graph.inputActive());
            assertNotNull(f.runtime.published.observe(f.holder(y)));
            graph.acknowledgeInputs(); f.drain();
            assertEquals(0L, f.runtime.inputs.measurements().get("consumers"));
            assertEquals(0L, f.runtime.inputs.measurements().get("expandedOperations"));
            assertEquals(0L, f.runtime.published.measurements().get("uses"));
            assertNotNull(f.runtime.published.observe(f.holder(y)), "consumer release preserves resident publication");
        } finally { f.stop(); }
        assertCleanup(f);
    }
}
