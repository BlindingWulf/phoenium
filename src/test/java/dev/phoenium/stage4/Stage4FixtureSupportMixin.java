package dev.phoenium.stage4;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.live.ExplicitLiveTopologyFixture;
import dev.phoenium.stage4probe.Stage4FixtureSupport;
import dev.phoenium.topology.RoutingKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Declares dependency support before native world startup; never publishes a live directory. */
@Mixin(ExplicitLiveTopologyFixture.class)
abstract class Stage4FixtureSupportMixin {
    @Inject(method = "plan(Ldev/phoenium/core/WorldId;Ldev/phoenium/topology/RoutingKey;"
        + "Ldev/phoenium/core/TopologyGeneration;Ldev/phoenium/core/OwnerEpoch;"
        + "Ldev/phoenium/core/OwnerEpoch;Ldev/phoenium/topology/RoutingKey;)"
        + "Ldev/phoenium/live/ExplicitLiveTopologyFixture$Plan;", at = @At("RETURN"), cancellable = true)
    private static void phoenium$declareStage4Support(WorldId world, RoutingKey anchor,
            TopologyGeneration generation, OwnerEpoch aEpoch, OwnerEpoch bEpoch, RoutingKey initialSpawn,
            CallbackInfoReturnable<ExplicitLiveTopologyFixture.Plan> callback) {
        int view = Integer.getInteger("phoenium.stage4.viewDistance", 8);
        int simulation = Integer.getInteger("phoenium.stage4.simulationDistance", 8);
        var original = callback.getReturnValue();
        var extended = Stage4FixtureSupport.extend(original, initialSpawn, view, simulation);
        if (!Boolean.getBoolean("phoenium.stage4.candidate")) extended = Stage4FixtureSupport.serial(extended);
        callback.setReturnValue(extended);
        System.out.println("STAGE4_FIXTURE_SUPPORT world=" + world.value() + " view=" + view
            + " simulation=" + simulation + " radius=" + Stage4FixtureSupport.supportRadius(view, simulation)
            + " added=" + (extended.routes().size() - original.routes().size()));
    }
}
