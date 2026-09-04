package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** PRQ-01 through PRQ-18: deterministic service proofs; these do not bind Minecraft callbacks. */
class ChunkPrerequisiteServicesTest {
    @Test void prq01PoiNormalInstallation() { Fixture f = new Fixture(); PoiInstallService.InstallId id = f.poi("s", 1); assertEquals(PoiInstallService.Claim.WON, f.services.poi().claim(id)); assertEquals(PoiInstallService.Settlement.INSTALLED, f.services.poi().installed(id)); assertTrue(f.services.poi().receipt(id).isPresent()); }
    @Test void prq02PoiReadBackgroundRaceSharesOneReceipt() { Fixture f = new Fixture(); PoiInstallService.InstallId id = f.poi("s", 1); assertEquals(PoiInstallService.Claim.WON, f.services.poi().claim(id)); assertEquals(PoiInstallService.Claim.INSTALLING, f.services.poi().claim(id)); assertEquals(PoiInstallService.Settlement.INSTALLED, f.services.poi().installed(id)); assertEquals(PoiInstallService.Claim.INSTALLED, f.services.poi().claim(id)); assertTrue(f.services.poi().receipt(id).isPresent()); }
    @Test void prq03PoiDuplicateDeliveryNoSecondInstall() { Fixture f = new Fixture(); PoiInstallService.InstallId id = f.poi("s", 1); f.services.poi().claim(id); f.services.poi().installed(id); assertEquals(PoiInstallService.Settlement.DUPLICATE, f.services.poi().installed(id)); }
    @Test void prq04PoiOldLifecycleRejected() { Fixture f = new Fixture(); PoiInstallService.InstallId old = f.poi("s", 1); f.replace(); assertEquals(PoiInstallService.Claim.STALE, f.services.poi().claim(old)); }
    @Test void prq05PoiPendingUnloadRescueRetainsReceipt() { Fixture f = new Fixture(); PoiInstallService.InstallId id = f.poi("s", 1); f.services.poi().claim(id); f.services.poi().installed(id); assertTrue(f.services.lifecycles().beginPendingUnload(f.lifecycle)); assertTrue(f.services.lifecycles().rescuePendingUnload(f.lifecycle, f.holder)); assertTrue(f.services.poi().receipt(id).isPresent()); }
    @Test void prq06LightNormalBatchReceipt() { Fixture f = new Fixture(); var batch = f.light(1); var block = f.input(LightInputService.Layer.BLOCK,"0",1); var sky = f.input(LightInputService.Layer.SKY,"0",2); assertTrue(f.services.light().register(batch,true,List.of(block,sky))); assertTrue(f.services.light().receipt(batch).isEmpty()); f.complete(batch); assertEquals(3,f.physical); assertTrue(f.services.light().receipt(batch).isPresent()); }
    @Test void prq07LightDuplicateSectionIsNotAppliedTwice() { Fixture f = new Fixture(); var batch = f.light(1); var input = f.input(LightInputService.Layer.BLOCK,"0",1); f.services.light().register(batch,false,List.of(input)); f.complete(batch); assertEquals(1,f.physical); assertEquals(ChunkStageAdmission.Result.DUPLICATE,f.executor.execute(f.pending,f.owner,new dev.phoenium.core.TopologyGeneration(1))); assertEquals(1,f.physical); }
    @Test void prq08LightDistinctSectionsAndLayersDoNotCollide() { Fixture f = new Fixture(); var batch = f.light(1); var a = f.input(LightInputService.Layer.BLOCK,"0",1); var b = f.input(LightInputService.Layer.BLOCK,"1",1); var c = f.input(LightInputService.Layer.SKY,"0",1); f.services.light().register(batch,false,List.of(a,b,c)); f.complete(batch); assertEquals(3,f.physical); assertEquals(3,f.services.light().snapshot(batch).orElseThrow().occurrences().stream().filter(o -> o.state() == LightInputService.State.ACCEPTED).count()); assertTrue(f.services.light().receipt(batch).isPresent()); }
    @Test void prq09LightOldLifecycleCannotSatisfyNew() { Fixture f = new Fixture(); LightInputService.BatchId old = f.light(1); f.services.light().register(old, false, List.of()); f.replace(); assertFalse(f.services.light().receipt(old).isPresent()); assertEquals(LoadApplyPrerequisites.State.STALE, f.services.prerequisites().evaluate(f.lifecycle, f.work, new LoadApplyPrerequisites.Required(java.util.Optional.empty(), java.util.Optional.of(old), true))); }
    @Test void prq10LightHasNoAsyncLeaseSurface() { Fixture f = new Fixture(); assertEquals(0, f.services.light().entries()); assertFalse(java.util.Arrays.stream(LightInputService.class.getDeclaredFields()).anyMatch(field -> field.getType().getSimpleName().contains("RegionLease"))); }
    @Test void prq11MetadataReturnsExactPreviousValue() { Fixture f = new Fixture(); assertEquals(ChunkTypeMetadataService.ABSENT, f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.PROTO).previous()); assertEquals((byte)-1, f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.FULL).previous()); }
    @Test void prq12MetadataSameKeyHasDeterministicOrder() { Fixture f = new Fixture(); f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.PROTO); ChunkTypeMetadataService.Update next = f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.FULL); assertEquals((byte)-1,next.previous()); assertEquals((byte)1,f.services.metadata().value(f.key).orElseThrow()); }
    @Test void prq13MetadataDistinctKeysAreIndependent() {
        Fixture f = new Fixture(); RoutingKey b = new RoutingKey(f.world, 5, 0);
        var lifecycleB = f.services.lifecycles().activateHolder(1,b,new Object());
        var workB = f.services.works().beginOrResolveSharedWork(lifecycleB,new ChunkWorkRegistry.WorkReservation("load","parsed",0));
        f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.PROTO);
        f.services.metadata().markPosition(workB,b,ChunkTypeMetadataService.Marker.FULL);
        assertEquals((byte)-1,f.services.metadata().value(f.key).orElseThrow());
        assertEquals((byte)1,f.services.metadata().value(b).orElseThrow());
    }
    @Test void prq14StaleMetadataCannotOverwriteNewLifecycle() { Fixture f = new Fixture(); ChunkCustodyModel.WorkId old = f.work; f.replace(); f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.FULL); assertEquals(ChunkTypeMetadataService.Result.STALE,f.services.metadata().markPosition(old,f.key,ChunkTypeMetadataService.Marker.PROTO).result()); assertEquals(ChunkTypeMetadataService.FULL,f.services.metadata().value(f.key).orElseThrow()); }
    @Test void prq15AOldReplaceableCannotDowngradeCurrentFull() { Fixture f = new Fixture(); ChunkCustodyModel.WorkId old = f.work; f.replace(); f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.FULL); assertEquals(ChunkTypeMetadataService.Result.STALE,f.services.metadata().markPositionReplaceable(old,f.key).result()); assertEquals(ChunkTypeMetadataService.FULL,f.services.metadata().value(f.key).orElseThrow()); }
    @Test void prq16FreshBranchNeedsOnlyActualRequirements() { Fixture f = new Fixture(); assertEquals(LoadApplyPrerequisites.State.READY,f.services.prerequisites().evaluate(f.lifecycle,f.work,LoadApplyPrerequisites.Required.fresh())); }
    @Test void prq17ReloadBranchWaitsForExactRegistrationThenReady() { Fixture f = new Fixture(); var poi = f.poi("0",1); var light = f.light(1); var block = f.input(LightInputService.Layer.BLOCK,"0",1); var required = LoadApplyPrerequisites.Required.reload(poi,light); assertEquals(LoadApplyPrerequisites.State.NOT_READY,f.services.prerequisites().evaluate(f.lifecycle,f.work,required)); f.services.poi().claim(poi); f.services.poi().installed(poi); assertEquals(LoadApplyPrerequisites.State.NOT_READY,f.services.prerequisites().evaluate(f.lifecycle,f.work,required)); f.services.light().register(light,true,List.of(block)); assertEquals(LoadApplyPrerequisites.State.READY,f.services.prerequisites().evaluate(f.lifecycle,f.work,required)); assertTrue(f.services.light().receipt(light).isEmpty()); assertEquals(0,f.physical); f.complete(light); assertTrue(f.services.light().receipt(light).isPresent()); }
    @Test void prq18ShutdownAndCleanupLeaveNoReceiptEntries() { Fixture f = new Fixture(); PoiInstallService.InstallId poi = f.poi("0",1); LightInputService.BatchId light = f.light(1); f.services.poi().claim(poi); f.services.light().register(light,false,List.of()); f.services.shutdown(); assertEquals(0,f.services.receiptEntries()); assertEquals(PoiInstallService.Claim.SHUTDOWN,f.services.poi().claim(poi)); assertEquals(LoadApplyPrerequisites.State.SHUTDOWN,f.services.prerequisites().evaluate(f.lifecycle,f.work,LoadApplyPrerequisites.Required.fresh())); }
    @Test void adversarialWrongWorldAndOldWorkReceiptsAreStale() { Fixture f = new Fixture(), foreign = new Fixture("other-world"); PoiInstallService.InstallId foreignPoi = foreign.poi("0",1); foreign.services.poi().claim(foreignPoi); foreign.services.poi().installed(foreignPoi); assertEquals(LoadApplyPrerequisites.State.STALE,f.services.prerequisites().evaluate(f.lifecycle,f.work,new LoadApplyPrerequisites.Required(java.util.Optional.of(foreignPoi),java.util.Optional.empty(),true))); ChunkCustodyModel.WorkId oldWork = f.services.works().beginOrResolveSharedWork(f.lifecycle,new ChunkWorkRegistry.WorkReservation("load","older",9)); PoiInstallService.InstallId oldPoi = new PoiInstallService.InstallId(f.lifecycle,oldWork,"0",9); f.services.poi().claim(oldPoi); f.services.poi().installed(oldPoi); assertEquals(LoadApplyPrerequisites.State.STALE,f.services.prerequisites().evaluate(f.lifecycle,f.work,new LoadApplyPrerequisites.Required(java.util.Optional.of(oldPoi),java.util.Optional.empty(),true))); }
    @Test void adversarialShutdownDuringIngressAndCurrentLifecycleMetadataAreDeterministic() { Fixture f = new Fixture(); PoiInstallService.InstallId poi = f.poi("0",1); LightInputService.BatchId light = f.light(1); f.services.poi().claim(poi); f.services.light().register(light,false,List.of()); f.services.poi().shutdown(); f.services.light().shutdown(); assertEquals(PoiInstallService.Settlement.SHUTDOWN,f.services.poi().installed(poi)); assertEquals(LightInputService.Readiness.SHUTDOWN,f.services.light().registeredForParsedApply(light,light.stage())); ChunkTypeMetadataService.Update first = f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.PROTO); ChunkTypeMetadataService.Update second = f.services.metadata().markPosition(f.work,f.key,ChunkTypeMetadataService.Marker.FULL); assertEquals(ChunkTypeMetadataService.Result.APPLIED,first.result()); assertEquals((byte)-1,second.previous()); }
    @Test void adversarialReplacementDuringCollectionCannotBecomeReady() { Fixture f = new Fixture(); PoiInstallService.InstallId poi = f.poi("0",1); LightInputService.BatchId light = f.light(1); f.services.light().register(light,false,List.of()); f.replace(); assertEquals(LoadApplyPrerequisites.State.STALE,f.services.prerequisites().evaluate(f.lifecycle,f.work,LoadApplyPrerequisites.Required.reload(poi,light))); }

    private static final class Fixture {
        final WorldId world; final RoutingKey key; final Object holder = new Object(); final ChunkPrerequisiteServices services; ChunkCustodyModel.LifecycleId lifecycle; ChunkCustodyModel.WorkId work;
        Fixture() { this("prq-world"); }
        Fixture(String worldName) { world = new WorldId(worldName); key = new RoutingKey(world, 4, 0); services = new ChunkPrerequisiteServices(world,"prq-server",1); lifecycle = services.lifecycles().activateHolder(1,key,holder); work = services.works().beginOrResolveSharedWork(lifecycle,new ChunkWorkRegistry.WorkReservation("load","parsed",0)); }
        PoiInstallService.InstallId poi(String section,long sequence) { return new PoiInstallService.InstallId(lifecycle,work,section,sequence); }
        LightInputService.BatchId light(long sequence) { return new LightInputService.BatchId(lifecycle,work,new ChunkCustodyModel.StageId(work,ChunkStageKind.PARSED_APPLY,"reload",sequence),sequence); }
        LightInputService.SectionInput input(LightInputService.Layer layer,String section,long sequence) { return new LightInputService.SectionInput(layer,section,sequence); }
        int physical;
        final dev.phoenium.region.RegionId owner = new dev.phoenium.region.RegionId(new WorldId("prq-world"),"A");
        ChunkStageExecutor executor;
        PendingVanillaStage<Void,Object> pending;
        void complete(LightInputService.BatchId batch) {
            var directory = new dev.phoenium.topology.RegionDirectory();
            var generation = new dev.phoenium.core.TopologyGeneration(1);
            directory.publish(generation,java.util.Map.of(key,new dev.phoenium.topology.RegionRoute(owner,generation,new dev.phoenium.core.OwnerEpoch(0))));
            executor = new ChunkStageExecutor(new ChunkStageAdmission(services.custody(),directory,
                    java.util.Map.of(owner,new dev.phoenium.ownership.RegionLeaseManager(owner)),new dev.phoenium.failure.FailureBarrier()));
            pending = new PendingVanillaStage<>(services.custody(),lifecycle,work,batch.stage(),new dev.phoenium.task.TaskCorrelationId(0),(input,claim) -> {
                for (var occurrence : services.light().snapshot(batch).orElseThrow().occurrences())
                    services.light().invoke(claim,occurrence.id(),insertion -> { physical++; insertion.inserted(); });
                assertTrue(services.light().receipt(batch).isEmpty());
                services.light().sealRead(claim,batch);
                return new Object();
            });
            pending.requireReloadLight(services.light(),batch); pending.bindInput(null); pending.publishReady();
            assertEquals(ChunkStageAdmission.Result.APPLIED,executor.execute(pending,owner,generation));
        }
        void replace() { services.lifecycles().retireHolder(lifecycle,holder); services.retire(lifecycle); lifecycle = services.lifecycles().activateHolder(1,key,new Object()); work = services.works().beginOrResolveSharedWork(lifecycle,new ChunkWorkRegistry.WorkReservation("load","parsed",1)); }
    }
}
