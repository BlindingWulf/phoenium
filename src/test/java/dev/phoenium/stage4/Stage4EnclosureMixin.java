package dev.phoenium.stage4;

import dev.phoenium.chunk.FullRegionalEnclosure;
import dev.phoenium.stage4probe.Stage4Trace;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RoutingKey;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bounded diagnostic of actual coordinator inputs; never changes a route or readiness. */
@Mixin(FullRegionalEnclosure.class)
abstract class Stage4EnclosureMixin {
    @Shadow @Final private RegionDirectory directory;
    @Unique private int phoenium$observed;

    @Inject(method = "enclose", at = @At("HEAD"))
    private void phoenium$observe(Set<RoutingKey> keys, CallbackInfoReturnable<Boolean> callback) {
        if (!Stage4Trace.active() || phoenium$observed >= 12) return;
        var routes = keys.stream().sorted(java.util.Comparator.comparingInt(RoutingKey::x)
            .thenComparingInt(RoutingKey::z)).map(key -> directory.resolve(key).orElse(null)).toList();
        long owners = routes.stream().filter(java.util.Objects::nonNull).map(route -> route.region()).distinct().count();
        if (owners <= 1) return;
        phoenium$observed++;
        System.out.println("STAGE4_ENCLOSURE generation=" + directory.snapshot().generation()
            + " keys=" + keys + " routes=" + routes + " stack="
            + StackWalker.getInstance().walk(frames -> frames.skip(1).limit(12).toList()));
    }
}
