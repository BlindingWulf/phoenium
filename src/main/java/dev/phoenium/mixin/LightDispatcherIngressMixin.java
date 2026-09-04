package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.chunk.LightDispatchHealth;
import dev.phoenium.chunk.LightMailboxCommand;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.util.thread.StrictQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkTaskDispatcher.class)
abstract class LightDispatcherIngressMixin {
    @WrapOperation(method = "submit(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V",
        at = @At(value = "INVOKE",target = "Lnet/minecraft/util/thread/PriorityConsecutiveExecutor;schedule(Ljava/lang/Runnable;)V"),require = 1,expect = 1)
    private void phoenium$mailbox(PriorityConsecutiveExecutor executor,Runnable scheduled,Operation<Void> original,
            @Local(argsOnly = true) Runnable input) {
        var witness = LightMailboxCommand.witness(input);
        if (witness == null) { original.call(executor,scheduled); return; }
        var priority = (StrictQueue.RunnableWithPriority) scheduled;
        Throwable previous = ((LightDispatchHealth) executor).phoenium$dispatchFailure();
        original.call(executor,new StrictQueue.RunnableWithPriority(priority.priority(),new LightMailboxCommand(priority.task(),witness)));
        Throwable failure = ((LightDispatchHealth) executor).phoenium$dispatchFailure();
        if (failure != null && failure != previous) witness.insertion().rejected(failure);
    }
}
