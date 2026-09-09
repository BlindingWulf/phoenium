package dev.phoenium.stage7.mixin;

import dev.phoenium.chunk.FullRegistrationRuntime;
import java.util.List;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One owner-free diagnostic cut per runtime; never retries or settles work. */
@Mixin(FullRegistrationRuntime.class)
abstract class Stage7StallMixin {
    @Shadow abstract List<FullRegistrationRuntime.Graph> graphSnapshot();
    @Unique private long stage7$firstPoll;
    @Unique private boolean stage7$reported;
    @Inject(method = "pumpOne", at = @At("RETURN"))
    private void observe(CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.getBoolean("phoenium.stage7.stallDiagnostics") || stage7$reported
                || dev.phoenium.ownership.OwnershipContext.current().isPresent()) return;
        if (stage7$firstPoll == 0) stage7$firstPoll = System.nanoTime();
        if (System.nanoTime() - stage7$firstPoll < 8_000_000_000L) return;
        stage7$reported = true;
        System.out.println("STAGE7_PENDING " + ((FullRegistrationRuntime) (Object) this).measurements()
                + " failure=" + dev.phoenium.Phoenium.liveRuntime().failureDescription());
        for (var graph : graphSnapshot()) {
            var evaluation = graph.inputEvaluation();
            System.out.println("STAGE7_GRAPH work=" + graph.work + " prepared=" + graph.prepared().isPresent()
                    + " state=" + evaluation.state() + " obligation=" + evaluation.obligation()
                    + " missing=" + graph.missingFinishingBindings());
        }
    }
}
