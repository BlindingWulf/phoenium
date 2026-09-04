package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Identity, physical-attempt, failure and terminal-resource adversaries supplementary to LIO. */
class LightIngressAdversarialTest {
    @ParameterizedTest @ValueSource(strings = {"world","lifecycle","work","stage","batch"})
    void wrongPhysicalLineageIsBarrierBeforeMinecraft(String mismatch) {
        var f = new LightProtocolFixture(); f.register(true);
        var life = f.lifecycle;
        if (mismatch.equals("world")) {
            var world = new WorldId("wrong");
            life = new ChunkCustodyModel.LifecycleId("lio",1,world,new RoutingKey(world,4,0),0);
        } else if (mismatch.equals("lifecycle")) {
            life = new ChunkCustodyModel.LifecycleId("lio",1,f.world,f.key,1);
        }
        var work = new ChunkCustodyModel.WorkId(life,mismatch.equals("work") ? 99 : f.work.workSequence());
        var stage = new ChunkCustodyModel.StageId(work,ChunkStageKind.PARSED_APPLY,"EMPTY",mismatch.equals("stage") ? 99 : 0);
        var wrong = new LightInputService.BatchId(life,work,stage,mismatch.equals("batch") ? 99 : 0);
        f.ready((input,claim) -> {
            f.light.invoke(claim,new LightInputService.OccurrenceId(wrong,LightInputService.Kind.RETAIN,"chunk",0),insertion -> {
                f.physical++; insertion.inserted();
            });
            return null;
        });
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver()); assertEquals(0,f.physical);
        assertTrue(f.light.receipt(f.batch).isEmpty()); f.cleanup();
    }

    @Test void sameScalarLineageFromAnotherServiceCannotAuthenticateAClaim() {
        var f = new LightProtocolFixture(); var other = new LightProtocolFixture(); f.register(true); other.register(true);
        assertEquals(f.batch,other.batch);
        f.ready((input,claim) -> {
            other.light.invoke(claim,other.occurrences().getFirst(),insertion -> { other.physical++; insertion.inserted(); });
            return null;
        });
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver()); assertEquals(0,other.physical);
        f.cleanup(); other.light.forget(other.batch);
    }

    @Test void sameCustodyButDifferentRegistrationCannotUsePinnedClaim() {
        var f = new LightProtocolFixture(); f.register(true);
        var other = new LightInputService(f.custody,f.services.works(),f.world,"lio",1);
        assertTrue(other.register(f.batch,true,List.of()));
        f.ready((input,claim) -> {
            other.invoke(claim,f.occurrences().getFirst(),insertion -> { f.physical++; insertion.inserted(); });
            return null;
        });
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver()); assertEquals(0,f.physical);
        f.cleanup(); other.forget(f.batch); assertEquals(0,other.entries());
    }

    @Test void unboundStageCannotUseAnOtherwiseRegisteredBatch() {
        var f = new LightProtocolFixture(); f.register(true);
        f.ready((input,claim) -> { f.submitAll(claim); return null; },false);
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver()); assertEquals(0,f.physical); f.cleanup();
    }

    @Test void lifecycleReplacementCannotOvertakeClaimedIngressAndCleanupIsVersionSpecific() {
        var f = new LightProtocolFixture(); f.register(true);
        AtomicReference<PendingVanillaStage<Void,Object>> replacement = new AtomicReference<>();
        AtomicReference<LightInputService.BatchId> nextBatch = new AtomicReference<>();
        java.util.List<String> accepted = new java.util.ArrayList<>();
        f.ready((input,claim) -> {
            var life = f.services.lifecycles().activateHolder(1,f.key,new Object());
            var work = f.services.works().beginOrResolveSharedWork(life,new ChunkWorkRegistry.WorkReservation("load","EMPTY",0));
            var stage = new ChunkCustodyModel.StageId(work,ChunkStageKind.PARSED_APPLY,"EMPTY",0);
            var batch = new LightInputService.BatchId(life,work,stage,0); nextBatch.set(batch);
            assertTrue(f.light.register(batch,true,List.of())); f.publish(f.b,2);
            var next = new PendingVanillaStage<Void,Object>(f.custody,life,work,stage,new dev.phoenium.task.TaskCorrelationId(1),
                (value,nextClaim) -> {
                    f.light.invoke(nextClaim,new LightInputService.OccurrenceId(batch,LightInputService.Kind.RETAIN,"chunk",0),
                        insertion -> { accepted.add("new"); insertion.inserted(); });
                    return f.light.sealRead(nextClaim,batch);
                });
            next.requireReloadLight(f.light,batch); next.bindInput(null); next.publishReady(); replacement.set(next);
            assertEquals(ChunkStageAdmission.Result.NOT_READY,f.executor.execute(next,f.b,new dev.phoenium.core.TopologyGeneration(2)));
            assertFalse(f.leaseB.hasActiveLease());
            assertThrows(ChunkProtocolException.class,() -> f.services.releaseRetiredLifecycle(f.lifecycle));
            f.light.forget(f.batch);
            f.light.invoke(claim,f.occurrences().getFirst(),insertion -> { accepted.add("old"); insertion.inserted(); });
            return f.light.sealRead(claim,f.batch);
        });
        assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        assertEquals(ChunkStageAdmission.Result.APPLIED,f.executor.execute(replacement.get(),f.b,new dev.phoenium.core.TopologyGeneration(2)));
        assertEquals(List.of("old","new"),accepted);
        f.services.releaseRetiredLifecycle(f.lifecycle);
        assertTrue(f.light.validates(f.light.receipt(nextBatch.get()).orElseThrow(),nextBatch.get()));
        f.services.custody().retire(nextBatch.get().lifecycle()); f.services.releaseRetiredLifecycle(nextBatch.get().lifecycle());
        assertEquals(0,f.custody.retainedEntries()); assertEquals(0,f.services.works().retainedEntries());
        assertEquals(0,f.services.lifecycles().retainedEntries()); assertEquals(0,f.light.entries());
    }

    @Test void acknowledgedLifecyclesReleaseAllRecordsWithoutReusingOldTokens() {
        var f = new LightProtocolFixture(); f.register(false);
        f.ready((input,claim) -> f.light.sealRead(claim,f.batch)); assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        f.custody.retire(f.lifecycle); f.services.releaseRetiredLifecycle(f.lifecycle);
        assertEquals(0,f.custody.retainedEntries()); assertEquals(0,f.services.works().retainedEntries());
        assertEquals(0,f.services.lifecycles().retainedEntries()); assertEquals(0,f.services.receiptEntries());
        assertEquals(0,f.services.metadata().entries()); assertFalse(f.pending.retainsInputOrContinuation());
        var next = f.services.lifecycles().activateHolder(1,f.key,f.holder);
        assertNotEquals(f.lifecycle,next); assertFalse(f.light.register(f.batch,false,List.of()));
        assertThrows(IllegalStateException.class,() -> f.custody.beginWork(f.lifecycle,0));
    }

    @Test void batchCannotBeReboundOrReusedByAnotherStage() {
        var f = new LightProtocolFixture(); f.register(false);
        assertThrows(ChunkProtocolException.class,() -> f.light.register(new LightInputService.BatchId(f.lifecycle,f.work,f.stage,1),false,List.of()));
        var otherStage = new ChunkCustodyModel.StageId(f.work,ChunkStageKind.PARSED_APPLY,"EMPTY",1);
        assertEquals(LightInputService.Readiness.STALE,f.light.registeredForParsedApply(f.batch,otherStage));
        var pending = new PendingVanillaStage<Void,Object>(f.custody,f.lifecycle,f.work,otherStage,new dev.phoenium.task.TaskCorrelationId(1),input -> new Object());
        assertThrows(ChunkProtocolException.class,() -> pending.requireReloadLight(f.light,f.batch));
        f.light.forget(f.batch); assertThrows(ChunkProtocolException.class,() -> f.light.register(f.batch,false,List.of()));
        assertEquals(0,f.light.entries());
    }

    @ParameterizedTest @ValueSource(strings = {"RETAIN","BLOCK","SKY"})
    void duplicatePhysicalAttemptCannotCrossCallBoundary(String kind) {
        var f = new LightProtocolFixture();
        f.register(true,f.input(LightInputService.Layer.BLOCK,"0",1),f.input(LightInputService.Layer.SKY,"0",2));
        var sequence = f.occurrences(); var duplicate = sequence.stream().filter(id -> id.kind().name().equals(kind)).findFirst().orElseThrow();
        f.ready((input,claim) -> {
            for (var id : sequence) {
                f.submit(claim,id);
                if (id.equals(duplicate)) f.submit(claim,id);
            }
            return f.light.sealRead(claim,f.batch);
        });
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver());
        assertEquals(sequence.indexOf(duplicate)+1,f.physical); assertTrue(f.light.receipt(f.batch).isEmpty());
        assertEquals(LightInputService.State.ACCEPTED,f.light.snapshot(f.batch).orElseThrow().occurrences().get(sequence.indexOf(duplicate)).state());
        f.cleanup(); assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver());
    }

    @Test void incompleteBatchCannotSealOnNormalReadReturn() {
        var f = new LightProtocolFixture(); f.register(true,f.input(LightInputService.Layer.BLOCK,"0",1));
        f.ready((input,claim) -> { f.submit(claim,f.occurrences().getFirst()); return f.light.sealRead(claim,f.batch); });
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver());
        assertEquals(1,f.physical); assertTrue(f.light.receipt(f.batch).isEmpty());
        assertEquals(LightInputService.State.REGISTERED,f.light.snapshot(f.batch).orElseThrow().occurrences().get(1).state()); f.cleanup();
    }

    @Test void normalCallReturnWithoutInsertionIsBarrier() {
        var f = new LightProtocolFixture(); f.register(true);
        f.ready((input,claim) -> { f.light.invoke(claim,f.occurrences().getFirst(),insertion -> { }); return null; });
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver());
        assertFalse(f.light.snapshot(f.batch).orElseThrow().occurrences().getFirst().inserted()); f.cleanup();
    }

    @Test void closedClaimAndInsertionWitnessCannotBeReusedAfterCleanup() {
        var f = new LightProtocolFixture(); f.register(true);
        var occurrence = f.occurrences().getFirst();
        AtomicReference<ChunkStageClaim> oldClaim = new AtomicReference<>();
        AtomicReference<LightInputService.Insertion> oldInsertion = new AtomicReference<>();
        f.ready((input,claim) -> {
            oldClaim.set(claim);
            f.light.invoke(claim,occurrence,insertion -> { oldInsertion.set(insertion); f.physical++; insertion.inserted(); });
            return f.light.sealRead(claim,f.batch);
        });
        assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver()); f.cleanup();
        assertThrows(ChunkProtocolException.class,() -> f.light.invoke(oldClaim.get(),occurrence,insertion -> f.physical++));
        assertThrows(ChunkProtocolException.class,() -> oldInsertion.get().inserted()); assertEquals(1,f.physical);
    }

    @Test void foreignContextCannotUseAnOtherwiseLiveExplicitClaim() {
        var f = new LightProtocolFixture(); f.register(true);
        // The active capability is invalid once its enclosing scope has exited; no ambient scope can revive it.
        AtomicReference<ChunkStageClaim> token = new AtomicReference<>();
        f.ready((input,claim) -> { token.set(claim); f.submitAll(claim); return f.light.sealRead(claim,f.batch); });
        assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        try (var lease = f.leaseB.acquire(); var context = dev.phoenium.ownership.OwnershipContext.enter(f.b,lease.epoch(),dev.phoenium.ownership.OperationCategory.OWNER_TASK)) {
            assertThrows(ChunkProtocolException.class,() -> token.get().require(f.stage));
        }
        f.cleanup();
    }

    @ParameterizedTest @ValueSource(strings = {"checked","runtime","error","completion","nestedCompletion"})
    void originalThrowableIdentityAndNestingAreUnchanged(String shape) {
        Throwable original = switch (shape) {
            case "checked" -> new IOException("exact");
            case "runtime" -> new IllegalStateException("ordinary Minecraft state error");
            case "error" -> new AssertionError("exact fatal");
            case "completion" -> new CompletionException(new IOException("exact cause"));
            default -> new CompletionException(new CompletionException(new IOException("exact nested cause")));
        };
        var f = new LightProtocolFixture(); f.register(true);
        f.ready((input,claim) -> { f.light.invoke(claim,f.occurrences().getFirst(),insertion -> { insertion.inserted(); throw original; }); return null; });
        assertEquals(ChunkStageAdmission.Result.FAILED_VANILLA,f.deliver()); assertSame(original,f.vanillaFailure());
        assertFalse(f.barrier.tripped()); assertTrue(f.light.receipt(f.batch).isEmpty()); f.cleanup();
    }

    @Test void shutdownAfterPrefixAndOriginalThrowReleasesOnlyAfterExit() throws Exception {
        var f = new MinecraftLightReadFixture(true,false); f.prepare(List.of(f.section(0,true,true,true),f.section(1,true,false,true)));
        f.beforeCall = kind -> { if (kind == LightInputService.Kind.SKY) f.services.shutdown(); };
        f.failKind = LightInputService.Kind.SKY; f.throwAfterInsertion = true; f.exactFailure = new IOException("shutdown prefix");
        AtomicReference<LightInputService.Snapshot> atExit = new AtomicReference<>();
        f.ready((input,claim) -> { try { return f.read(claim); } catch (Throwable failure) { atExit.set(f.light.snapshot(f.batch).orElseThrow()); throw failure; } });
        assertEquals(ChunkStageAdmission.Result.FAILED_VANILLA,f.deliver()); assertSame(f.exactFailure,f.vanillaFailure());
        assertEquals(List.of(LightInputService.State.ACCEPTED,LightInputService.State.ACCEPTED,LightInputService.State.FAILED_VANILLA,LightInputService.State.REGISTERED),
                atExit.get().occurrences().stream().map(LightInputService.OccurrenceSnapshot::state).toList());
        assertEquals(0,f.light.entries()); assertEquals(3,f.physical); assertFalse(f.barrier.tripped()); f.cleanup();
    }

    @Test void emptyReloadRequiresNormalReadCompletionAndCanAuthenticateReceipt() throws Exception {
        var f = new MinecraftLightReadFixture(true,false); f.prepare(List.of());
        assertTrue(f.light.receipt(f.batch).isEmpty());
        f.ready((input,claim) -> f.read(claim)); assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        assertTrue(f.readReturned); assertEquals(0,f.physical); assertTrue(f.light.validates(f.light.receipt(f.batch).orElseThrow(),f.batch)); f.cleanup();
    }

    @Test void realReadFailureAfterCompleteIngressCannotSealBeforeNormalReturn() throws Exception {
        var f = new MinecraftLightReadFixture(true,false); f.malformedReadSuffix = true;
        f.prepare(List.of(f.section(0,true,true,true)));
        AtomicReference<Throwable> original = new AtomicReference<>();
        f.ready((input,claim) -> { try { return f.read(claim); } catch (Throwable failure) { original.set(failure); throw failure; } });
        assertEquals(ChunkStageAdmission.Result.FAILED_VANILLA,f.deliver());
        assertSame(original.get(),f.vanillaFailure()); assertInstanceOf(NullPointerException.class,original.get());
        assertEquals(3,f.physical); assertFalse(f.readReturned); assertTrue(f.light.receipt(f.batch).isEmpty());
        assertTrue(f.light.snapshot(f.batch).orElseThrow().occurrences().stream().allMatch(o -> o.state() == LightInputService.State.ACCEPTED));
        assertFalse(f.barrier.tripped()); f.cleanup();
    }

    @Test void repeatedSectionCoordinatesRemainSeparateOriginalOccurrences() throws Exception {
        var f = new MinecraftLightReadFixture(true,false);
        f.prepare(List.of(f.section(0,true,true,true),f.section(0,true,true,true)));
        var ids = f.occurrences(); assertEquals(5,ids.size()); assertEquals(5,ids.stream().distinct().count());
        f.ready((input,claim) -> f.read(claim)); assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        assertEquals(5,f.physical); assertEquals(2,f.order.stream().filter("BLOCK:0"::equals).count());
        assertEquals(2,f.order.stream().filter("SKY:0"::equals).count()); f.cleanup();
    }
}
