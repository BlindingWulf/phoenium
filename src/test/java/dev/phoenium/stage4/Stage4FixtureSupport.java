package dev.phoenium.stage4probe;

import dev.phoenium.live.ExplicitLiveTopologyFixture;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Shared finite startup geometry, packaged only in the disposable acceptance probe. */
public final class Stage4FixtureSupport {
    private Stage4FixtureSupport() { }

    public static int supportRadius(int viewDistance, int simulationDistance) {
        if (viewDistance < 2 || viewDistance > 32 || simulationDistance < 2 || simulationDistance > 32)
            throw new IllegalArgumentException("invalid configured Stage 4 distance");
        int nativeDependencies = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)
            .getAccumulatedRadiusOf(ChunkStatus.EMPTY);
        int forcedFullHalo = 1 + ChunkLevel.byStatus(ChunkStatus.FULL) - ChunkMap.FORCED_TICKET_LEVEL;
        // Simulation tickets are anchored at ENTITY_TICKING, two levels inside FULL. Their
        // outer FULL and generation rings exceed the block-ticking receipt's one-chunk halo.
        int simulationFullRadius = simulationDistance + ChunkLevel.byStatus(ChunkStatus.FULL)
            - ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING);
        return Math.max(Math.max(viewDistance + 1, simulationFullRadius), forcedFullHalo) + nativeDependencies;
    }

    public static ExplicitLiveTopologyFixture.Plan extend(ExplicitLiveTopologyFixture.Plan plan,
            RoutingKey initialSpawn, int viewDistance, int simulationDistance) {
        if (!plan.anchor().world().value().equals("minecraft:overworld")) return plan;
        int radius = supportRadius(viewDistance, simulationDistance);
        RegionRoute supportOwner = plan.routes().values().stream()
            .filter(route -> route.region().equals(plan.a())).findFirst().orElseThrow();
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>(plan.routes());
        for (int[] destination : new int[][] {{0, 0}, {7, 0}, {32, 0}, {32, 12}})
            add(routes, new RoutingKey(plan.anchor().world(), destination[0], destination[1]), radius, supportOwner);
        add(routes, plan.anchor(), radius + 5, supportOwner); // Native spawn search extent.
        if (initialSpawn != null) add(routes, initialSpawn, radius, supportOwner);
        return new ExplicitLiveTopologyFixture.Plan(plan.a(), plan.b(), plan.anchor(), Map.copyOf(routes));
    }

    /** The serial oracle still needs explicit movement authority. Only its initial owner
     * assignment differs; the finite native support keys and all gameplay inputs are shared. */
    public static ExplicitLiveTopologyFixture.Plan serial(ExplicitLiveTopologyFixture.Plan plan) {
        RegionRoute owner = plan.routes().get(plan.anchor());
        if (owner == null || !owner.region().equals(plan.a())) throw new IllegalStateException("missing serial anchor");
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
        plan.routes().keySet().forEach(key -> routes.put(key, owner));
        return new ExplicitLiveTopologyFixture.Plan(plan.a(), plan.b(), plan.anchor(), Map.copyOf(routes));
    }

    private static void add(Map<RoutingKey, RegionRoute> routes, RoutingKey center, int radius, RegionRoute owner) {
        for (int x = center.x() - radius; x <= center.x() + radius; x++)
            for (int z = center.z() - radius; z <= center.z() + radius; z++)
                routes.putIfAbsent(new RoutingKey(center.world(), x, z), owner);
    }
}
