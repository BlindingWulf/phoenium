package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.service.NeighborCascadeContinuation;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** S8-F6: the native updater keeps running its own steps. Phoenium only observes each step at
 * its runNext call and may cut the cascade there; addAndRun defers roots behind a cut. Both
 * method bodies are pinned by {@code NeighborCascadePin}. */
@Mixin(CollectingNeighborUpdater.class)
abstract class NeighborCascadeUpdaterMixin implements NeighborCascadeContinuation.Port {
    @Shadow @Final private Level level;
    @Shadow @Final private int maxChainedNeighborUpdates;
    @Shadow @Final private ArrayDeque<?> stack;
    @Shadow @Final private List<?> addedThisLayer;
    @Shadow private int count;
    @Shadow private void runUpdates() { throw new AssertionError(); }

    @Override public Object phoenium$cascadeLevel() { return level; }
    @SuppressWarnings("unchecked")
    @Override public ArrayDeque<Object> phoenium$cascadeStack() { return (ArrayDeque<Object>) stack; }
    @SuppressWarnings("unchecked")
    @Override public List<Object> phoenium$cascadeLayer() { return (List<Object>) addedThisLayer; }
    @Override public int phoenium$cascadeCount() { return count; }
    @Override public void phoenium$cascadeCount(int value) { count = value; }
    @Override public int phoenium$cascadeMaxChained() { return maxChainedNeighborUpdates; }
    @Override public void phoenium$cascadeRunUpdates() { runUpdates(); }

    @WrapOperation(method = "runUpdates()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater$NeighborUpdates;runNext(Lnet/minecraft/world/level/Level;)Z"),
        require = 1, expect = 1)
    private boolean phoenium$cascadeStep(@Coerce Object step, Level stepLevel, Operation<Boolean> original) {
        return NeighborCascadeContinuation.runNext(this, step, () -> original.call(step, stepLevel));
    }

    @Inject(method = "addAndRun(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater$NeighborUpdates;)V",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void phoenium$cascadeRoot(BlockPos pos, @Coerce Object update, CallbackInfo ci) {
        if (NeighborCascadeContinuation.deferRoot(this, pos, update)) ci.cancel();
    }
}
