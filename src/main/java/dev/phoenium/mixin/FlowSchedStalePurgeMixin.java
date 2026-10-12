package dev.phoenium.mixin;

import dev.phoenium.chunk.FlowSchedQueuePurge;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
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
 * S9-M6b (MEM-6, D40): FlowSched's bucket queue compacts buckets whose superseded entries pile up (see
 * {@link FlowSchedQueuePurge}). Hooked after {@code enqueue0}, which every enqueue and re-prioritisation goes through.
 */
@Pseudo
@Mixin(targets = "com.ishland.flowsched.executor.BucketTaskPriorityQueue", remap = false)
abstract class FlowSchedStalePurgeMixin {
    @Shadow(remap = false) @Final private ConcurrentLinkedQueue<?>[] priorities;
    @Shadow(remap = false) @Final private AtomicIntegerArray taskCount;
    @Unique private final FlowSchedQueuePurge.State phoenium$purge = new FlowSchedQueuePurge.State();

    @Inject(method = "enqueue0", at = @At("TAIL"), remap = false, require = 1, expect = 1)
    private void phoenium$boundSupersededEntries(@Coerce Object task, int priority, CallbackInfo ci) {
        if (FlowSchedQueuePurge.ENABLED)
            FlowSchedQueuePurge.afterEnqueue(phoenium$purge, priorities, taskCount, getClass().getClassLoader());
    }
}
