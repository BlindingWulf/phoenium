package dev.phoenium.live;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Development-only, finite A/B geometry for real-client readiness work.  It is enabled solely by
 * {@value #PROPERTY}; production/default serial boot retains its existing Stage 2 world-default
 * layout.  The fixture has no overlapping ownership and deliberately has no default route.
 */
public final class ExplicitLiveTopologyFixture {
    public static final String PROPERTY = "phoenium.stage3.explicitTopology";
    public static final int A_RADIUS = 4;
    public static final int B_SEPARATED_RADIUS = 16;
    public static final int B_WIDTH = 4;
    /** The gated trace target sits outside the server's persistent spawn area. */
    public static final int TRACE_B_WIDTH = 28;
    /** Spawn search (5), client view (5 + one border), and generation dependencies (11). */
    public static final int CHUNK_SUPPORT_RADIUS = 22;
    private ExplicitLiveTopologyFixture() { }

    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }

    public static Plan plan(WorldId world, RoutingKey spawnAnchor, TopologyGeneration generation,
                            OwnerEpoch aEpoch, OwnerEpoch bEpoch) {
        return plan(world, spawnAnchor, generation, aEpoch, bEpoch, null);
    }

    public static Plan plan(WorldId world, RoutingKey spawnAnchor, TopologyGeneration generation,
                            OwnerEpoch aEpoch, OwnerEpoch bEpoch, RoutingKey initialSpawn) {
        RegionId a = new RegionId(world, "stage3-fixture-a");
        RegionId b = new RegionId(world, "stage3-fixture-b");
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
        boolean chunkSupport = initialSpawn != null;
        int supportRadius = chunkSupport ? CHUNK_SUPPORT_RADIUS : A_RADIUS;
        for (int x = spawnAnchor.x() - supportRadius; x <= spawnAnchor.x() + supportRadius; x++) {
            for (int z = spawnAnchor.z() - supportRadius; z <= spawnAnchor.z() + supportRadius; z++) {
                RoutingKey key = new RoutingKey(world, x, z);
                routes.put(key, new RegionRoute(a, generation, aEpoch));
            }
        }
        if (chunkSupport) {
            if (!world.equals(initialSpawn.world())) throw new IllegalArgumentException("foreign spawn support world");
            // Declare the actual sampler-selected spawn neighborhood and the harness corridor's
            // dependency neighborhood before any stage is admitted. No missing-route fallback.
            for (int x = initialSpawn.x() - CHUNK_SUPPORT_RADIUS; x <= initialSpawn.x() + CHUNK_SUPPORT_RADIUS; x++)
                for (int z = initialSpawn.z() - CHUNK_SUPPORT_RADIUS; z <= initialSpawn.z() + CHUNK_SUPPORT_RADIUS; z++)
                    routes.put(new RoutingKey(world, x, z), new RegionRoute(a, generation, aEpoch));
            if (Integer.getInteger("phoenium.stage3.explicitSeparateBX") == null)
            for (int x = spawnAnchor.x() + supportRadius + 1; x <= spawnAnchor.x() + A_RADIUS
                    + Math.max(TRACE_B_WIDTH, Integer.getInteger("phoenium.stage3.explicitBWidth", 0)) + CHUNK_SUPPORT_RADIUS; x++)
                for (int z = spawnAnchor.z() - supportRadius; z <= spawnAnchor.z() + supportRadius; z++)
                    routes.put(new RoutingKey(world, x, z), new RegionRoute(a, generation, aEpoch));
        }
        // Development-only: a wider B strip keeps its far end beyond regional-closure convergence.
        Integer separated = Integer.getInteger("phoenium.stage3.explicitSeparateBX");
        if (separated != null) {
            // Development-only: B is a B_SEPARATED_RADIUS square around chunk (separated, anchor.z),
            // fully disjoint from A and from every A support strip, so no footprint can span both
            // regions and regional enclosure has nothing to converge.
            routes.keySet().removeIf(key -> Math.abs(key.x() - separated) <= B_SEPARATED_RADIUS + 1
                && Math.abs(key.z() - spawnAnchor.z()) <= B_SEPARATED_RADIUS + 1);
            for (int x = separated - B_SEPARATED_RADIUS; x <= separated + B_SEPARATED_RADIUS; x++)
                for (int z = spawnAnchor.z() - B_SEPARATED_RADIUS; z <= spawnAnchor.z() + B_SEPARATED_RADIUS; z++)
                    routes.put(new RoutingKey(world, x, z), new RegionRoute(b, generation, bEpoch));
            return new Plan(a, b, spawnAnchor, Map.copyOf(routes));
        }
        int bWidth = Integer.getInteger("phoenium.stage3.explicitBWidth",
            Boolean.getBoolean("phoenium.stage3.chunkTrace") ? TRACE_B_WIDTH : B_WIDTH);
        for (int x = spawnAnchor.x() + A_RADIUS + 1; x <= spawnAnchor.x() + A_RADIUS + bWidth; x++) {
            for (int z = spawnAnchor.z() - A_RADIUS; z <= spawnAnchor.z() + A_RADIUS; z++) {
                RoutingKey key = new RoutingKey(world, x, z);
                RegionRoute previous = routes.put(key, new RegionRoute(b, generation, bEpoch));
                if (previous != null && !chunkSupport) {
                    throw new IllegalStateException("explicit fixture created overlapping authority");
                }
            }
        }
        return new Plan(a, b, spawnAnchor, Map.copyOf(routes));
    }

    public record Plan(RegionId a, RegionId b, RoutingKey anchor, Map<RoutingKey, RegionRoute> routes) { }
}
