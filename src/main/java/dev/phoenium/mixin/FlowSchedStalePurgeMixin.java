package dev.phoenium.mixin;

import dev.phoenium.chunk.FlowSchedQueuePurge;
import java.util.concurrent.Semaphore;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-M6b (MEM-6, D40): FlowSched's executor compacts queue buckets whose superseded entries pile up, retiring one free
 * permit per dropped entry (see {@link FlowSchedQueuePurge}). Hooked after {@code reschedule0}, which every schedule,
 * re-prioritisation and lock-listener re-enqueue goes through, once its permit has been released.
 */
@Pseudo
@Mixin(targets = "com.ishland.flowsched.executor.ExecutorManager", remap = false)
abstract class FlowSchedStalePurgeMixin {
    @Shadow(remap = false) @Final public Semaphore waitObj;
    @Unique private final FlowSchedQueuePurge.State phoenium$purge = new FlowSchedQueuePurge.State();
    @Unique private static volatile boolean phoenium$purgeBroken;

    @Inject(method = "reschedule0", at = @At("TAIL"), remap = false, require = 1, expect = 1)
    private void phoenium$boundSupersededEntries(@Coerce Object task, int priority, CallbackInfo ci) {
        if (phoenium$purgeBroken) return;
        try {
            if (FlowSchedQueuePurge.ENABLED) FlowSchedQueuePurge.afterEnqueue(phoenium$purge, this, waitObj);
        } catch (LinkageError failure) {
            // D40 review R3: FlowSchedQueuePurge failed to initialise (ExceptionInInitializerError, then NoClassDefFoundError
            // on every call). Never let that reach reschedule0: switch the hook off, once, visibly.
            phoenium$purgeBroken = true;
            try {
                org.slf4j.LoggerFactory.getLogger("Phoenium").error("S9-M6b FlowSched queue compaction hook disabled", failure);
            } catch (Throwable ignored) {          // logging is best effort here
            }
        } catch (Throwable ignored) {
            // afterEnqueue swallows and counts its own failures; anything still here is transient optional work
        }
    }
}
