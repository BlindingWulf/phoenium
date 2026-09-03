package dev.phoenium;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.Map;

final class CoreFixture {
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
    CoreFixture() {
        directory.publish(new TopologyGeneration(1), Map.of(
            aKey, new RegionRoute(aId, new TopologyGeneration(1), a.ownerEpoch()),
            bKey, new RegionRoute(bId, new TopologyGeneration(1), b.ownerEpoch())));
        scheduler = new RegionScheduler(runtimes, directory, barrier);
        gateway = new OwnerGateway(directory, runtimes, scheduler);
    }
    RegionTaskEnvelope task(RoutingKey key, TaskPayload payload, AdmissionPolicy policy, long sequence) {
        RegionRoute route = directory.resolve(key).orElseThrow();
        return new RegionTaskEnvelope(payload.getClass().getSimpleName(), payload, key, route.generation(), route.ownerEpoch(), "test", new TaskCorrelationId(sequence), new TaskSequence(sequence), policy);
    }
    private static RegionRuntime ready(RegionId id) { RegionRuntime runtime = new RegionRuntime(id, new OwnerEpoch(0)); runtime.transitionTo(RegionLifecycle.READY); return runtime; }
}
