package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.chunk.LightInvocationArgument;
import dev.phoenium.chunk.LightMailboxCommand;
import java.util.function.IntSupplier;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(ThreadedLevelLightEngine.class)
abstract class LightEngineIngressMixin {
    @WrapOperation(method = "retainData(Lnet/minecraft/world/level/ChunkPos;Z)V",
        at = @At(value = "INVOKE",target = "Lnet/minecraft/server/level/ThreadedLevelLightEngine;addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V"),require = 1,expect = 1)
    private void phoenium$retain(ThreadedLevelLightEngine engine,int x,int z,IntSupplier priority,@Coerce Object type,
            Runnable task,Operation<Void> original,@Local(argsOnly = true) ChunkPos position) {
        var witness = ((LightInvocationArgument) (Object) position).phoenium$lightWitness();
        original.call(engine,x,z,priority,type,witness == null ? task : new LightMailboxCommand(task,witness));
    }
    @WrapOperation(method = "queueSectionData(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/DataLayer;)V",
        at = @At(value = "INVOKE",target = "Lnet/minecraft/server/level/ThreadedLevelLightEngine;addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V"),require = 1,expect = 1)
    private void phoenium$section(ThreadedLevelLightEngine engine,int x,int z,IntSupplier priority,@Coerce Object type,
            Runnable task,Operation<Void> original,@Local(argsOnly = true) SectionPos position) {
        var witness = ((LightInvocationArgument) position).phoenium$lightWitness();
        original.call(engine,x,z,priority,type,witness == null ? task : new LightMailboxCommand(task,witness));
    }
    @WrapOperation(method = "addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V",
        at = @At(value = "INVOKE",target = "Lnet/minecraft/server/level/ChunkTaskDispatcher;submit(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V"),require = 1,expect = 1)
    private void phoenium$dispatch(ChunkTaskDispatcher dispatcher,Runnable command,long key,IntSupplier priority,
            Operation<Void> original,@Local(argsOnly = true) Runnable input) {
        var witness = LightMailboxCommand.witness(input);
        original.call(dispatcher,witness == null ? command : new LightMailboxCommand(command,witness),key,priority);
    }
}
