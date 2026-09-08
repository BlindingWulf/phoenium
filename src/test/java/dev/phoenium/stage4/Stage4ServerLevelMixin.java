package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
abstract class Stage4ServerLevelMixin {
    private static boolean phoenium$nestedQueued;
    @Inject(method = "blockEvent(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V", at = @At("HEAD"), require = 1)
    private void phoenium$enqueue(BlockPos pos, Block block, int a, int b, CallbackInfo ci) {
        if (Stage4Trace.fixture(pos)) Stage4Trace.event("block.enqueue", "pos=" + pos + " block=" + Stage4Trace.block(block) + " a=" + a + " b=" + b);
    }

    @Inject(method = "runBlockEvents()V", at = @At("HEAD"), require = 1)
    private void phoenium$runBegin(CallbackInfo ci) { }

    @Inject(method = "runBlockEvents()V", at = @At("RETURN"), require = 1)
    private void phoenium$runEnd(CallbackInfo ci) { }

    @Inject(method = "doBlockEvent(Lnet/minecraft/world/level/BlockEventData;)Z", at = @At("HEAD"), require = 1)
    private void phoenium$dispatch(BlockEventData event, CallbackInfoReturnable<Boolean> cir) {
        if (Stage4Trace.fixture(event.pos())) Stage4Trace.event("block.dispatch", "pos=" + event.pos() + " block=" + Stage4Trace.block(event.block()) + " a=" + event.paramA() + " b=" + event.paramB());
        if (!phoenium$nestedQueued && event.block() == Blocks.NOTE_BLOCK && event.pos().equals(new BlockPos(70, 65, 8)) && event.paramA() == 1) {
            phoenium$nestedQueued = true;
            BlockPos nested = event.pos().above();
            ServerLevel level = (ServerLevel) (Object) this;
            level.setBlock(nested, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
            level.blockEvent(nested, Blocks.NOTE_BLOCK, 2, event.paramB() + 1);
            Stage4Trace.event("block.nested.enqueue", "pos=" + nested + " block=" + Stage4Trace.block(Blocks.NOTE_BLOCK));
        }
    }

    @Inject(method = "tickFluid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;)V", at = @At("HEAD"), require = 1)
    private void phoenium$fluid(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (Stage4Trace.fixture(pos)) Stage4Trace.event("tick.fluid", "pos=" + pos + " fluid=" + fluid + " gameTime=" + ((ServerLevel) (Object) this).getGameTime());
    }

    @Inject(method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"), require = 1)
    private void phoenium$block(BlockPos pos, Block block, CallbackInfo ci) {
        if (Stage4Trace.fixture(pos)) Stage4Trace.event("tick.block.vanilla", "pos=" + pos + " block=" + Stage4Trace.block(block));
    }
}
