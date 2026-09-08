package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Test-only observation of the native persistent tick container restore boundary. */
@Mixin(LevelChunkTicks.class)
abstract class Stage4LevelChunkTicksMixin<T> {
    @Shadow private List<SavedTick<T>> pendingTicks;

    @WrapMethod(method = "unpack(J)V")
    private void phoenium$observeUnpack(long currentTick, Operation<Void> original) {
        List<SavedTick<T>> pendingBefore = pendingTicks;
        original.call(currentTick);
        Stage4Trace.observeUnpack((LevelChunkTicks<?>) (Object) this, currentTick, pendingBefore);
    }
}
