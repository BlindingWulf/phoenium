package dev.phoenium.acceptance;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.live.ExplicitLiveTopologyFixture;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FULL acceptance-only topology declaration for the saved villager's bed lookup.
 *
 * The production explicit fixture deliberately has no world-default route.  This mixin adds
 * only the finite vanilla FULL dependency square needed by the preserved sleeping position.
 * It is packaged in full-live-probe.jar and never enters the production Phoenium JAR.
 */
@Mixin(ExplicitLiveTopologyFixture.class)
abstract class FullRuntimeAcceptanceBedSupportMixin {
    private static final int BED_CHUNK_X = 127;
    private static final int BED_CHUNK_Z = -69;

    @Inject(method = "plan(Ldev/phoenium/core/WorldId;Ldev/phoenium/topology/RoutingKey;"
            + "Ldev/phoenium/core/TopologyGeneration;Ldev/phoenium/core/OwnerEpoch;"
            + "Ldev/phoenium/core/OwnerEpoch;Ldev/phoenium/topology/RoutingKey;)"
            + "Ldev/phoenium/live/ExplicitLiveTopologyFixture$Plan;",
            at = @At("RETURN"), cancellable = true)
    private static void phoenium$addSavedBedSupport(
            dev.phoenium.core.WorldId world,
            RoutingKey spawnAnchor,
            TopologyGeneration generation,
            OwnerEpoch aEpoch,
            OwnerEpoch bEpoch,
            RoutingKey initialSpawn,
            CallbackInfoReturnable<ExplicitLiveTopologyFixture.Plan> callback) {
        if (!Boolean.getBoolean("phoenium.test.fullBedSupport")) return;

        ExplicitLiveTopologyFixture.Plan plan = callback.getReturnValue();
        RegionRoute ownerRoute = plan.routes().values().stream()
                .filter(route -> route.region().equals(plan.a()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("FULL bed support has no owner route"));
        int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)
                .getAccumulatedRadiusOf(ChunkStatus.EMPTY);
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>(plan.routes());
        for (int x = BED_CHUNK_X - radius; x <= BED_CHUNK_X + radius; x++) {
            for (int z = BED_CHUNK_Z - radius; z <= BED_CHUNK_Z + radius; z++) {
                RoutingKey key = new RoutingKey(world, x, z);
                RegionRoute previous = routes.put(key, ownerRoute);
                if (previous != null && !previous.region().equals(plan.a())) {
                    throw new IllegalStateException("FULL bed support overlaps B authority at " + key);
                }
            }
        }
        callback.setReturnValue(new ExplicitLiveTopologyFixture.Plan(plan.a(), plan.b(), plan.anchor(), routes));
    }
}
