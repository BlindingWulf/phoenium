package dev.phoenium.stage7.mixin;

import dev.phoenium.core.*;
import dev.phoenium.live.ExplicitLiveTopologyFixture;
import dev.phoenium.topology.*;
import java.util.*;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.*;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Declare the moved test workload's real native dependency halo before startup.
 * This creates neither tickets nor chunks and never changes an active directory. */
@Mixin(ExplicitLiveTopologyFixture.class)
abstract class Stage7FixtureSupportMixin {
    @WrapMethod(method = "plan(Ldev/phoenium/core/WorldId;Ldev/phoenium/topology/RoutingKey;"
            + "Ldev/phoenium/core/TopologyGeneration;Ldev/phoenium/core/OwnerEpoch;"
            + "Ldev/phoenium/core/OwnerEpoch;Ldev/phoenium/topology/RoutingKey;)"
            + "Ldev/phoenium/live/ExplicitLiveTopologyFixture$Plan;")
    private static ExplicitLiveTopologyFixture.Plan support(WorldId world, RoutingKey anchor, TopologyGeneration generation,
                                OwnerEpoch aEpoch, OwnerEpoch bEpoch, RoutingKey initialSpawn,
                                Operation<ExplicitLiveTopologyFixture.Plan> operation) {
        var original = operation.call(world, anchor, generation, aEpoch, bEpoch, initialSpawn);
        if (!Boolean.getBoolean("phoenium.stage7.fixtureSupport") || !world.value().equals("minecraft:overworld")) return original;
        var routes = new LinkedHashMap<>(original.routes());
        var owner = Objects.requireNonNull(routes.get(anchor));
        int radius = 1 + ChunkLevel.byStatus(ChunkStatus.FULL) - ChunkMap.FORCED_TICKET_LEVEL
                + ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL).getAccumulatedRadiusOf(ChunkStatus.EMPTY);
        for (var center : System.getProperty("phoenium.stage5.centers").split(",")) {
            int cx = Integer.parseInt(center);
            for (int x = cx - radius; x <= cx + radius; x++) for (int z = -radius; z <= radius; z++)
                routes.putIfAbsent(new RoutingKey(world, x, z), owner);
        }
        System.out.println("STAGE7_FIXTURE_DECLARED nativeRadius=" + radius + " added=" + (routes.size() - original.routes().size()));
        return new ExplicitLiveTopologyFixture.Plan(original.a(), original.b(), original.anchor(), Map.copyOf(routes));
    }
}
