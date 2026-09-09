package dev.phoenium.stage7.mixin;

import dev.phoenium.stage7.Stage7ConcurrentProbe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractFurnaceBlockEntity.class)
abstract class Stage7FurnaceMixin {
    @Inject(method = "serverTick", at = @At("HEAD"))
    private static void before(ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity entity, CallbackInfo ci) {
        Stage7ConcurrentProbe.furnace(level, pos, entity, "before");
    }
    @Inject(method = "serverTick", at = @At("RETURN"))
    private static void after(ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity entity, CallbackInfo ci) {
        Stage7ConcurrentProbe.furnace(level, pos, entity, "after");
    }
}
