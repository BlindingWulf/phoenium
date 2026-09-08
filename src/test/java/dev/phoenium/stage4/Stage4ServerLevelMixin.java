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
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(ServerLevel.class)
abstract class Stage4ServerLevelMixin {
    private static boolean phoenium$nestedQueued;
    @WrapMethod(method = "blockEvent(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V")
    private void phoenium$enqueue(BlockPos pos, Block block, int a, int b, Operation<Void> original) {
        try { original.call(pos, block, a, b); }
        finally {
            if (Stage4Trace.fixture(pos))
                Stage4Trace.event("block.enqueue", "pos=" + pos + " block=" + Stage4Trace.block(block) + " a=" + a + " b=" + b);
        }
    }

    @WrapMethod(method = "runBlockEvents()V")
    private void phoenium$runEnd(Operation<Void> original) {
        try {
            original.call();
        } finally {
            if (Stage4Trace.active() && Boolean.getBoolean("phoenium.stage4.candidate")) {
                var services = dev.phoenium.Phoenium.liveRuntime().regionalServices();
                if (services != null) {
                    var deferred = services.world((ServerLevel) (Object) this).events().measurements().deferred();
                    if (deferred > 0) Stage4Trace.event("block.deferred", "count=" + deferred);
                }
            }
        }
    }

    @Inject(method = "doBlockEvent(Lnet/minecraft/world/level/BlockEventData;)Z", at = @At("HEAD"), require = 1)
    private void phoenium$dispatch(BlockEventData event, CallbackInfoReturnable<Boolean> cir) {
        if (Stage4Trace.fixture(event.pos())) Stage4Trace.event("block.dispatch", "pos=" + event.pos() + " block=" + Stage4Trace.block(event.block()) + " a=" + event.paramA() + " b=" + event.paramB()
            + " " + Stage4Trace.authority((ServerLevel) (Object) this, event.pos()));
        if (Stage4Trace.fixture(event.pos())) Stage4Trace.bodyObserved((ServerLevel) (Object) this, event.pos(), "block.dispatch");
        if (!phoenium$nestedQueued && event.block() == Blocks.NOTE_BLOCK && event.pos().equals(new BlockPos(8, 65, 8)) && event.paramA() == 1) {
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
        if (Stage4Trace.fixture(pos)) Stage4Trace.event("tick.fluid", "pos=" + pos + " fluid=" + fluid + " gameTime=" + ((ServerLevel) (Object) this).getGameTime()
            + " " + Stage4Trace.authority((ServerLevel) (Object) this, pos));
        if (Stage4Trace.fixture(pos)) Stage4Trace.bodyObserved((ServerLevel) (Object) this, pos, "fluid.tick");
    }

    @Inject(method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"), require = 1)
    private void phoenium$block(BlockPos pos, Block block, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (Stage4Trace.fixture(pos)) Stage4Trace.event("tick.block.vanilla", "pos=" + pos + " block=" + Stage4Trace.block(block)
            + " gameTime=" + level.getGameTime() + " " + Stage4Trace.authority(level, pos));
        if (Stage4Trace.fixture(pos)) Stage4Trace.bodyObserved(level, pos, "block.tick");
        if (Stage4Trace.reloadEffect(pos) && block == Blocks.NOTE_BLOCK) {
            level.setBlock(pos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            Stage4Trace.event("effect.tick.reached", "pos=" + pos + " gameTime=" + level.getGameTime());
            System.out.println("STAGE4_TRACE_RELOAD_EFFECT reached=true gameTime=" + level.getGameTime());
        }
    }
}
