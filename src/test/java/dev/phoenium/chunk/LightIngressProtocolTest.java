package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.task.TaskCorrelationId;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** LIO-01 through LIO-16. Original Minecraft methods run in the bounded read/dispatcher fixture. */
class LightIngressProtocolTest {
    @Test void lio01ReloadClaimsBeforeReceiptAndPreservesRealSectionOrder() throws Exception {
        for (boolean installed : List.of(false, true)) {
            var f = new MinecraftLightReadFixture(true, installed);
            f.prepare(List.of(f.section(0,true,true,true), f.section(1,true,true,false),
                    f.section(0,true,true,true), f.section(2,false,false,true)));
            assertEquals(LoadApplyPrerequisites.State.READY, f.services.prerequisites().evaluate(f.lifecycle,f.work,f.stage,
                    LoadApplyPrerequisites.Required.reload(f.poi,f.batch)));
            assertTrue(f.light.receipt(f.batch).isEmpty()); assertEquals(0,f.physical);
            f.ready((input,claim) -> f.read(claim));
            assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
            assertInstanceOf(ProtoChunk.class,f.successfulValue());
            assertEquals(List.of("POI:0","RETAIN","BLOCK:0","SKY:0","BLOCK:1","SKY:1",
                    "POI:0","BLOCK:0","SKY:0","POI:2","READ_RETURN","SEALED"),f.order);
            f.cleanup();
        }
    }

    @Test void lio02FreshDoesNotRequireAnyReloadLightState() throws Exception {
        var f = new MinecraftLightReadFixture(true,false);
        var exact = new java.util.concurrent.atomic.AtomicReference<Object>();
        assertEquals(LoadApplyPrerequisites.State.READY,f.services.prerequisites().evaluate(f.lifecycle,f.work,LoadApplyPrerequisites.Required.fresh()));
        var parsed = new CompletableFuture<Void>(); var poi = new CompletableFuture<Void>();
        var ready = parsed.thenCombine(poi,(a,b) -> {
            f.ready((input,claim) -> { Object value = f.fresh(claim); exact.set(value); return value; },false);
            return true;
        });
        parsed.complete(null); assertFalse(ready.isDone()); assertNull(f.pending);
        poi.complete(null); assertTrue(ready.getNow(false));
        assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver()); assertSame(exact.get(),f.successfulValue());
        assertInstanceOf(ProtoChunk.class,exact.get());
        assertEquals(0,f.light.entries()); assertEquals(0,f.physical); f.cleanup();
    }

    @Test void lio03OriginalRetainIsOnceBeforeFirstInputAndAbsentForEmpty() throws Exception {
        for (boolean hasInput : List.of(false,true)) {
            var f = new MinecraftLightReadFixture(true,false);
            f.prepare(List.of(f.section(0,hasInput,false,true)));
            f.ready((input,claim) -> f.read(claim));
            assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
            assertEquals(hasInput ? 1 : 0,f.order.stream().filter("RETAIN"::equals).count());
            if (hasInput) assertTrue(f.order.indexOf("RETAIN") < f.order.indexOf("BLOCK:0"));
            assertTrue(f.light.receipt(f.batch).isPresent()); f.cleanup();
        }
    }

    @Test void lio04EveryOriginalBlockPayloadAndSectionIsSubmittedExactlyOnce() throws Exception {
        var f = new MinecraftLightReadFixture(false,true);
        f.prepare(List.of(f.section(0,true,false,true),f.section(1,true,false,true)));
        f.ready((input,claim) -> f.read(claim));
        assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        assertEquals(3,f.physical); assertEquals(1,f.order.stream().filter("BLOCK:0"::equals).count());
        assertEquals(1,f.order.stream().filter("BLOCK:1"::equals).count()); f.cleanup();
    }

    @Test void lio05SkyOnlyAndDisabledSkyPreserveActualVanillaShape() throws Exception {
        for (boolean sky : List.of(false,true)) {
            var f = new MinecraftLightReadFixture(sky,false);
            f.prepare(List.of(f.section(0,false,true,true),f.section(1,false,false,true)));
            f.ready((input,claim) -> f.read(claim));
            assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
            assertEquals(sky ? 2 : 0,f.physical); assertEquals(sky ? 1 : 0,f.order.stream().filter("SKY:0"::equals).count());
            f.cleanup();
        }
    }

    @Test void lio06ReceiptNeedsEveryInsertionNormalReturnAndAuthenticIssuance() {
        var f = new LightProtocolFixture(); f.register(true,f.input(LightInputService.Layer.BLOCK,"0",1));
        var forged = new LightInputReceipt(f.batch);
        assertFalse(f.light.validates(forged,f.batch));
        f.ready((input,claim) -> {
            f.submitAll(claim); assertTrue(f.light.receipt(f.batch).isEmpty());
            return f.light.sealRead(claim,f.batch);
        });
        assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        assertFalse(f.light.validates(forged,f.batch));
        assertTrue(f.light.validates((LightInputReceipt)f.successfulValue(),f.batch)); f.cleanup();
    }

    @Test void lio07IngressCompletesWhileActualEngineProcessingRemainsPending() throws Exception {
        var f = new MinecraftLightReadFixture(true,false); f.prepare(List.of(f.section(0,true,true,true)));
        f.ready((input,claim) -> f.read(claim));
        assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        assertTrue(f.pending.outcome().isDone()); assertTrue(f.light.receipt(f.batch).isPresent());
        assertFalse(f.processing.isEmpty(),"real dispatcher processing deliberately remains pending");
        assertFalse(f.leaseA.hasActiveLease()); assertNull(f.activeClaim); f.cleanup();
    }

    @Test void lio08DuplicateReadinessAndDeliveriesBeforeDuringAfterClaimDoNotReplay() {
        var f = new LightProtocolFixture(); f.register(true,f.input(LightInputService.Layer.BLOCK,"0",1));
        f.ready((input,claim) -> {
            assertFalse(f.pending.bindInput(null));
            assertEquals(ChunkStageAdmission.Result.DUPLICATE,f.deliver());
            f.submitAll(claim); return f.light.sealRead(claim,f.batch);
        });
        assertFalse(f.pending.bindInput(null)); assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        assertFalse(f.pending.bindInput(null)); assertEquals(ChunkStageAdmission.Result.DUPLICATE,f.deliver());
        assertEquals(2,f.physical); f.cleanup();
        assertEquals(ChunkStageAdmission.Result.DUPLICATE,f.deliver());
        assertThrows(ChunkProtocolException.class,() -> f.light.register(f.batch,false,List.of()));
    }

    @Test void lio09OldLifecycleCannotSubmitAfterReplacementBeforeClaim() {
        var f = new LightProtocolFixture(); f.register(false);
        f.ready((input,claim) -> { fail("old lifecycle invoked"); return null; });
        var next = f.services.lifecycles().activateHolder(1,f.key,new Object());
        assertNotEquals(f.lifecycle,next); assertEquals(ChunkStageAdmission.Result.SUPERSEDED,f.deliver());
        assertEquals(0,f.physical); assertFalse(f.barrier.tripped()); f.cleanup();
    }

    @Test void lio10SupersededWorkCannotSubmitButIndependentDemandAndStatusesSurvive() {
        var stale = new LightProtocolFixture(); stale.register(false);
        stale.ready((input,claim) -> { fail("retired work invoked"); return null; });
        stale.services.works().retireWork(stale.work,false);
        assertEquals(ChunkStageAdmission.Result.SUPERSEDED,stale.deliver()); assertEquals(0,stale.physical); stale.cleanup();
        var shared = new LightProtocolFixture(); shared.register(false);
        shared.services.works().removeDemand(shared.services.works().addDemand(shared.work));
        shared.services.works().beginOrResolveSharedWork(shared.lifecycle,new ChunkWorkRegistry.WorkReservation("load","LIGHT",1));
        shared.ready((input,claim) -> shared.light.sealRead(claim,shared.batch));
        assertEquals(ChunkStageAdmission.Result.APPLIED,shared.deliver()); shared.cleanup();
    }

    @Test void lio11ClaimKeepsFrozenAuthorityAndPublicationUsesTheNewRoute() throws Exception {
        for (boolean changeOwner : List.of(false,true)) {
            var f = new MinecraftLightReadFixture(true,false); f.prepare(List.of(f.section(0,true,true,true)));
            f.beforeCall = kind -> { if (kind == LightInputService.Kind.BLOCK) f.publish(changeOwner ? f.b : f.a,2); };
            f.ready((input,claim) -> f.read(claim)); assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
            assertEquals(3,f.physical); assertEquals(ChunkStageAdmission.Result.APPLIED,publishEmpty(f,f.light.receipt(f.batch).orElseThrow(),new AtomicInteger()));
            f.cleanup();
        }
    }

    @Test void lio12GenuinePartialAndEnqueueBeforeThrowFailuresNeverReplay() throws Exception {
        for (boolean afterInsertion : List.of(false,true)) {
            var f = new MinecraftLightReadFixture(true,false);
            f.prepare(List.of(f.section(0,true,true,true),f.section(1,true,false,true)));
            f.failKind = LightInputService.Kind.SKY; f.exactFailure = new IOException("exact original SKY failure");
            f.throwAfterInsertion = afterInsertion;
            f.ready((input,claim) -> f.read(claim));
            assertEquals(ChunkStageAdmission.Result.FAILED_VANILLA,f.deliver()); assertSame(f.exactFailure,f.vanillaFailure());
            var states = f.light.snapshot(f.batch).orElseThrow().occurrences();
            assertEquals(List.of(LightInputService.State.ACCEPTED,LightInputService.State.ACCEPTED,
                    LightInputService.State.FAILED_VANILLA,LightInputService.State.REGISTERED),states.stream().map(LightInputService.OccurrenceSnapshot::state).toList());
            assertEquals(afterInsertion,states.get(2).inserted()); assertFalse(f.readReturned); assertTrue(f.light.receipt(f.batch).isEmpty());
            assertFalse(f.barrier.tripped()); assertEquals(ChunkStageAdmission.Result.DUPLICATE,f.deliver()); assertEquals(3,f.physical);
            AtomicInteger recovered = new AtomicInteger(); Object recoveryResult = new Object();
            var recoveredFuture = CompletableFuture.failedFuture(f.vanillaFailure()).exceptionally(error -> {
                assertSame(f.exactFailure,error); recovered.incrementAndGet(); return recoveryResult;
            });
            assertSame(recoveryResult,recoveredFuture.getNow(null)); assertEquals(1,recovered.get()); f.cleanup();
        }
        var later = new MinecraftLightReadFixture(true,false); later.prepare(List.of(later.section(0,true,true,true)));
        IOException failure = new IOException("later original body failure");
        later.ready((input,claim) -> { later.read(claim); throw failure; });
        assertEquals(ChunkStageAdmission.Result.FAILED_VANILLA,later.deliver()); assertSame(failure,later.vanillaFailure());
        assertTrue(later.light.receipt(later.batch).isPresent(),"ingress fact remains distinct from reload success");
        assertFalse(later.barrier.tripped()); later.cleanup();
    }

    @Test void lio13ShutdownFenceAndActualSwallowedDispatchRejectionAreAccounted() throws Exception {
        var losing = new LightProtocolFixture(); losing.register(false);
        losing.ready((input,claim) -> { fail("shutdown loser invoked"); return null; }); losing.services.shutdown();
        assertEquals(ChunkStageAdmission.Result.SHUTDOWN,losing.deliver()); losing.cleanup();
        for (var boundary : List.of(LightInputService.Kind.RETAIN,LightInputService.Kind.SKY)) {
            var f = new MinecraftLightReadFixture(true,false); f.prepare(List.of(f.section(0,true,true,true)));
            f.beforeCall = kind -> { if (kind == boundary) f.services.shutdown(); };
            f.ready((input,claim) -> f.read(claim)); assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
            assertEquals(3,f.physical); assertEquals(0,f.light.entries()); f.cleanup();
        }
        var rejected = new MinecraftLightReadFixture(true,false); rejected.prepare(List.of(rejected.section(0,true,true,true)));
        rejected.rejectDispatch = true; rejected.ready((input,claim) -> rejected.read(claim));
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,rejected.deliver());
        assertTrue(rejected.light.snapshot(rejected.batch).orElseThrow().occurrences().getFirst().inserted());
        assertTrue(rejected.light.receipt(rejected.batch).isEmpty()); assertEquals(1,rejected.physical); rejected.cleanup();
    }

    @Test void lio14SupersessionAfterClaimPreservesPrefixAndFencesOldPublication() throws Exception {
        var f = new MinecraftLightReadFixture(true,false); f.prepare(List.of(f.section(0,true,true,true)));
        f.beforeCall = kind -> {
            if (kind == LightInputService.Kind.BLOCK) {
                f.services.works().retireWork(f.work,false); f.light.forget(f.batch);
            }
        };
        f.ready((input,claim) -> f.read(claim)); assertEquals(ChunkStageAdmission.Result.APPLIED,f.deliver());
        assertEquals(3,f.physical); assertEquals(0,f.light.entries());
        assertFalse(f.light.validates(new LightInputReceipt(f.batch),f.batch)); f.cleanup();
    }

    @Test void lio15EveryTerminalPathReleasesProtocolRecordsAndRetainedInputs() {
        for (String terminal : List.of("success","vanilla","barrier","superseded","shutdown")) {
            var f = new LightProtocolFixture(); f.register(false);
            f.ready((input,claim) -> {
                if (terminal.equals("vanilla")) throw new IOException("data");
                if (terminal.equals("barrier")) throw new ChunkProtocolException("adapter");
                return f.light.sealRead(claim,f.batch);
            });
            if (terminal.equals("superseded")) f.services.works().retireWork(f.work,false);
            if (terminal.equals("shutdown")) f.services.shutdown();
            f.deliver(); assertTrue(f.pending.outcome().isDone()); f.cleanup();
            assertFalse(f.pending.bindInput(null));
            assertEquals(0,f.light.entries());
            f.custody.retire(f.lifecycle); f.services.releaseRetiredLifecycle(f.lifecycle);
            assertEquals(0,f.custody.retainedEntries()); assertEquals(0,f.services.lifecycles().retainedEntries());
            assertEquals(0,f.services.works().retainedEntries()); assertEquals(0,f.services.receiptEntries());
            assertEquals(0,f.services.metadata().entries());
        }
    }

    @Test void lio16NormalReloadAndEmptyPublicationCannotSucceedWithoutRealReceipt() {
        var f = new LightProtocolFixture(); f.register(false);
        f.ready((input,claim) -> new Object());
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,f.deliver()); assertTrue(f.light.receipt(f.batch).isEmpty());
        assertInstanceOf(ChunkStageOutcome.BarrierFailure.class,f.pending.outcome().getNow(null)); f.cleanup();
        var publication = new LightProtocolFixture(); publication.register(false);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER,publishEmpty(publication,new LightInputReceipt(publication.batch),calls));
        assertEquals(0,calls.get()); publication.light.forget(publication.batch);
    }

    private static ChunkStageAdmission.Result publishEmpty(LightProtocolFixture f, LightInputReceipt receipt, AtomicInteger calls) {
        var stage = new ChunkCustodyModel.StageId(f.work,ChunkStageKind.STATUS_PUBLISH,"EMPTY",1);
        var pending = new PendingVanillaStage<Void,Object>(f.custody,f.lifecycle,f.work,stage,new TaskCorrelationId(1),
                (input,claim) -> { calls.incrementAndGet(); return receipt; });
        pending.requireReloadPublication(f.light,receipt); pending.bindInput(null); pending.publishReady();
        var route = f.directory.resolve(f.key).orElseThrow();
        return f.executor.execute(pending,route.region(),route.generation());
    }
}
