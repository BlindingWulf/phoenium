package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Scripted controller: producers only publish; tests explicitly choose delivery/fence order. */
class LightProtocolFixture {
    final WorldId world = new WorldId("light-test");
    final RoutingKey key = new RoutingKey(world, 4, 0);
    final Object holder = new Object();
    final ChunkPrerequisiteServices services = new ChunkPrerequisiteServices(world, "lio", 1);
    final ChunkCustodyModel custody = services.custody();
    final ChunkCustodyModel.LifecycleId lifecycle = services.lifecycles().activateHolder(1, key, holder);
    final ChunkCustodyModel.WorkId work = services.works().beginOrResolveSharedWork(lifecycle,
            new ChunkWorkRegistry.WorkReservation("load", "EMPTY", 0));
    final ChunkCustodyModel.StageId stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.PARSED_APPLY, "EMPTY", 0);
    final LightInputService.BatchId batch = new LightInputService.BatchId(lifecycle, work, stage, 0);
    final PoiInstallService.InstallId poi = new PoiInstallService.InstallId(lifecycle, work, "all", 0);
    final LightInputService light = services.light();
    final RegionId a = new RegionId(world, "A"), b = new RegionId(world, "B");
    final RegionLeaseManager leaseA = new RegionLeaseManager(a), leaseB = new RegionLeaseManager(b);
    final RegionDirectory directory = new RegionDirectory();
    final FailureBarrier barrier = new FailureBarrier();
    final ChunkStageAdmission admission = new ChunkStageAdmission(custody, directory, Map.of(a, leaseA, b, leaseB), barrier);
    final ChunkStageExecutor executor = new ChunkStageExecutor(admission);
    PendingVanillaStage<Void, Object> pending;
    int physical;

    LightProtocolFixture() { publish(a, 1); }
    void publish(RegionId owner, long generation) {
        var g = new TopologyGeneration(generation);
        directory.publish(g, Map.of(key, new RegionRoute(owner, g, new OwnerEpoch(0))));
    }
    void installPoi() {
        assertEquals(PoiInstallService.Claim.WON, services.poi().claim(poi));
        assertEquals(PoiInstallService.Settlement.INSTALLED, services.poi().installed(poi));
    }
    void register(boolean retain, LightInputService.SectionInput... inputs) {
        assertTrue(light.register(batch, retain, List.of(inputs)));
    }
    LightInputService.SectionInput input(LightInputService.Layer layer, String section, long sequence) {
        return new LightInputService.SectionInput(layer, section, sequence);
    }
    List<LightInputService.OccurrenceId> occurrences() {
        return light.snapshot(batch).orElseThrow().occurrences().stream().map(LightInputService.OccurrenceSnapshot::id).toList();
    }
    void submit(ChunkStageClaim claim, LightInputService.OccurrenceId id) throws Throwable {
        light.invoke(claim, id, insertion -> {
            assertFalse(custody.gate().isHeldByCurrentThread(), "metadata lock across physical call");
            assertTrue(leaseA.hasActiveLease() || leaseB.hasActiveLease());
            assertEquals(LightInputService.State.INVOKING, light.snapshot(batch).orElseThrow().occurrences().stream()
                    .filter(o -> o.id().equals(id)).findFirst().orElseThrow().state());
            physical++; insertion.inserted();
        });
    }
    void submitAll(ChunkStageClaim claim) throws Throwable { for (var occurrence : occurrences()) submit(claim, occurrence); }
    void ready(VanillaChunkStage.Admitted<Void, Object> body) { ready(body, true); }
    void ready(VanillaChunkStage.Admitted<Void, Object> body, boolean reload) {
        pending = new PendingVanillaStage<>(custody, lifecycle, work, stage, new TaskCorrelationId(0), body);
        if (reload) pending.requireReloadLight(light, batch);
        pending.bindInput(null); pending.publishReady();
    }
    ChunkStageAdmission.Result deliver() {
        var route = directory.resolve(key).orElseThrow();
        return executor.execute(pending, route.region(), route.generation());
    }
    Object successfulValue() { return ((ChunkStageOutcome.Applied<?>) pending.outcome().getNow(null)).value(); }
    Throwable vanillaFailure() { return ((ChunkStageOutcome.VanillaFailure<?>) pending.outcome().getNow(null)).failure(); }
    void cleanup() {
        light.forget(batch);
        assertEquals(0, light.entries()); assertEquals(0, light.occurrenceEntries());
        assertFalse(leaseA.hasActiveLease()); assertFalse(leaseB.hasActiveLease());
        if (pending != null) assertFalse(pending.retainsInputOrContinuation());
    }
}
