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
    public static final int B_WIDTH = 4;
    private ExplicitLiveTopologyFixture() { }

    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }

    public static Plan plan(WorldId world, RoutingKey spawnAnchor, TopologyGeneration generation,
                            OwnerEpoch aEpoch, OwnerEpoch bEpoch) {
        RegionId a = new RegionId(world, "stage3-fixture-a");
        RegionId b = new RegionId(world, "stage3-fixture-b");
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
        for (int x = spawnAnchor.x() - A_RADIUS; x <= spawnAnchor.x() + A_RADIUS; x++) {
            for (int z = spawnAnchor.z() - A_RADIUS; z <= spawnAnchor.z() + A_RADIUS; z++) {
                RoutingKey key = new RoutingKey(world, x, z);
                routes.put(key, new RegionRoute(a, generation, aEpoch));
            }
        }
        for (int x = spawnAnchor.x() + A_RADIUS + 1; x <= spawnAnchor.x() + A_RADIUS + B_WIDTH; x++) {
            for (int z = spawnAnchor.z() - A_RADIUS; z <= spawnAnchor.z() + A_RADIUS; z++) {
                RoutingKey key = new RoutingKey(world, x, z);
                if (routes.put(key, new RegionRoute(b, generation, bEpoch)) != null) {
                    throw new IllegalStateException("explicit fixture created overlapping authority");
                }
            }
        }
        return new Plan(a, b, spawnAnchor, Map.copyOf(routes));
    }

    public record Plan(RegionId a, RegionId b, RoutingKey anchor, Map<RoutingKey, RegionRoute> routes) { }
}
