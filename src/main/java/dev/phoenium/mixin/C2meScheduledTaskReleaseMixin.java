package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.C2meTaskRelease;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * S9-M6 (MEM-6): a C2ME worldgen task drops its action once the action has started.
 *
 * <p>FlowSched's bucket queue re-enqueues a task whose priority changes and leaves the old entry in its old bucket until
 * that bucket is polled. C2ME enqueues worldgen tasks before the chunk's level priority is known (the lowest bucket) and
 * re-prioritises them when it arrives, so each runs from its new bucket while a stale entry stays in the lowest one, which
 * is polled only when every other bucket is empty: never under sustained generation. The stale entry keeps the finished
 * ScheduledTask, whose action lambda captures the ProtoChunk (with its NoiseChunk, carving masks and sections), the
 * loading context and the neighbourhood's holders. Under exploration those finished tasks pinned tens of thousands of
 * proto chunks until the heap ran out (MEM-6).
 *
 * <p>The action runs exactly once (the worker runs a task only after taking its locks; a polled task is marked
 * removed), and nothing reads it afterwards: the completion callback uses the task's future, lock release and post-exec
 * list. So the field is cleared right after {@code action.get()} returns or throws; the stale queue entry then holds only
 * the task shell. {@code -Dphoenium.c2me.releaseFinishedTasks=false} keeps the action (D35 behaviour).
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.base.common.scheduler.ScheduledTask", remap = false)
abstract class C2meScheduledTaskReleaseMixin {
    @Shadow(remap = false) @Mutable @Final private Supplier<?> action;

    @WrapOperation(method = "run(Ljava/lang/Runnable;)V", remap = false,
            at = @At(value = "INVOKE", target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;", remap = false),
            require = 1, expect = 1)
    private Object phoenium$releaseAction(Supplier<?> supplier, Operation<Object> original) {
        try {
            return original.call(supplier);
        } finally {
            if (C2meTaskRelease.ENABLED) {
                this.action = null;
                C2meTaskRelease.released();
            }
        }
    }
}
