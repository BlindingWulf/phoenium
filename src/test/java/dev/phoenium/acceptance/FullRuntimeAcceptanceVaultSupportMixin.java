package dev.phoenium.acceptance;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.live.ExplicitLiveTopologyFixture;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only finite route declaration for the preserved vault chunk. */
@Mixin(ExplicitLiveTopologyFixture.class)
abstract class FullRuntimeAcceptanceVaultSupportMixin {
    private static final int VAULT_CHUNK_X = 142;
    private static final int VAULT_CHUNK_Z = -31;

    @Inject(method = "plan(Ldev/phoenium/core/WorldId;Ldev/phoenium/topology/RoutingKey;"
            + "Ldev/phoenium/core/TopologyGeneration;Ldev/phoenium/core/OwnerEpoch;"
            + "Ldev/phoenium/core/OwnerEpoch;Ldev/phoenium/topology/RoutingKey;)"
            + "Ldev/phoenium/live/ExplicitLiveTopologyFixture$Plan;",
            at = @At("RETURN"), cancellable = true)
    private static void phoenium$addVaultSupport(
            dev.phoenium.core.WorldId world, RoutingKey spawnAnchor, TopologyGeneration generation,
            OwnerEpoch aEpoch, OwnerEpoch bEpoch, RoutingKey initialSpawn,
            CallbackInfoReturnable<ExplicitLiveTopologyFixture.Plan> callback) {
        if (!Boolean.getBoolean("phoenium.test.vaultDiagnostic")) return;
        ExplicitLiveTopologyFixture.Plan plan = callback.getReturnValue();
        RegionRoute ownerRoute = plan.routes().values().stream()
                .filter(route -> route.region().equals(plan.a())).findFirst()
                .orElseThrow(() -> new IllegalStateException("vault diagnostic has no owner route"));
        int forcedFullHalo = ChunkLevel.byStatus(ChunkStatus.FULL) - ChunkMap.FORCED_TICKET_LEVEL;
        int generationRadius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)
                .getAccumulatedRadiusOf(ChunkStatus.EMPTY);
        int radius = forcedFullHalo + generationRadius;
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>(plan.routes());
        for (int x = VAULT_CHUNK_X - radius; x <= VAULT_CHUNK_X + radius; x++)
            for (int z = VAULT_CHUNK_Z - radius; z <= VAULT_CHUNK_Z + radius; z++)
                routes.put(new RoutingKey(world, x, z), ownerRoute);
        callback.setReturnValue(new ExplicitLiveTopologyFixture.Plan(plan.a(), plan.b(), plan.anchor(), routes));
    }
}
