package dev.phoenium;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.live.ExplicitLiveTopologyFixture;
import dev.phoenium.stage4probe.Stage4FixtureSupport;
import dev.phoenium.topology.RoutingKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Stage4FixtureSupportTest {
    @Test void declaredDestinationsIncludeNativeDependenciesAtBothConfiguredDistances() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        WorldId world = new WorldId("minecraft:overworld");
        RoutingKey anchor = new RoutingKey(world, 0, 0);
        var original = ExplicitLiveTopologyFixture.plan(world, anchor, new TopologyGeneration(1),
            new OwnerEpoch(0), new OwnerEpoch(0), anchor);
        assertFalse(original.routes().containsKey(new RoutingKey(world, 32, 26)),
            "old z=0 corridor cannot cover the deferred destination's forced FULL dependency square");
        for (int distance : new int[] {5, 8}) {
            var extended = Stage4FixtureSupport.extend(original, anchor, distance, distance);
            original.routes().forEach((key, route) -> assertEquals(route, extended.routes().get(key)));
            int radius = Stage4FixtureSupport.supportRadius(distance, distance);
            for (int x = 32 - radius; x <= 32 + radius; x++)
                for (int z = 12 - radius; z <= 12 + radius; z++)
                    assertNotNull(extended.routes().get(new RoutingKey(world, x, z)));
            assertFalse(extended.routes().containsKey(new RoutingKey(world, 1000, 1000)));
        }
    }
}
