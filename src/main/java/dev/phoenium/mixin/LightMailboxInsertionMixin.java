package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.LightDispatchHealth;
import dev.phoenium.chunk.LightMailboxCommand;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.util.thread.AbstractConsecutiveExecutor;
import net.minecraft.util.thread.StrictQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractConsecutiveExecutor.class)
abstract class LightMailboxInsertionMixin implements LightDispatchHealth {
    @Unique private volatile Throwable phoenium$dispatchFailure;
    @Override public Throwable phoenium$dispatchFailure() { return phoenium$dispatchFailure; }
    @WrapOperation(method = "schedule(Ljava/lang/Runnable;)V",at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/thread/StrictQueue;push(Ljava/lang/Runnable;)Z"),require = 1,expect = 1)
    private boolean phoenium$insert(StrictQueue<?> queue,Runnable task,Operation<Boolean> original) {
        boolean accepted = original.call(queue,task);
        var witness = LightMailboxCommand.witness(task);
        if (accepted && witness != null) witness.insertion().inserted();
        return accepted;
    }
    @WrapOperation(method = "registerForExecution()V",at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/Executor;execute(Ljava/lang/Runnable;)V"),require = 2,expect = 2)
    private void phoenium$rejection(Executor executor,Runnable task,Operation<Void> original) {
        try { original.call(executor,task); }
        catch (RejectedExecutionException failure) { phoenium$dispatchFailure = failure; throw failure; }
    }
}
