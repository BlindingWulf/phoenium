package dev.phoenium.stage4;

import dev.phoenium.stage5probe.Stage5FurnaceObservations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class Stage5FurnaceTraceMixin {
    @Inject(method = "serverTick", at = @At("HEAD"))
    private static void before(ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity entity, CallbackInfo ci) {
        if (Boolean.getBoolean("phoenium.stage5.adverse")) dev.phoenium.adversestop.AdverseStopProbe.originalEnter(pos);
        Stage5FurnaceObservations.observe(level, pos, entity, "before");
    }
    @Inject(method = "serverTick", at = @At("RETURN"))
    private static void after(ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity entity, CallbackInfo ci) {
        Stage5FurnaceObservations.observe(level, pos, entity, "after");
        if (Boolean.getBoolean("phoenium.stage5.adverse")) dev.phoenium.adversestop.AdverseStopProbe.originalReturn(pos);
    }
}
