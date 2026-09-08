package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
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

    /**
     * Observe the original event body through its invocation boundary.  A production
     * cancellable HEAD admission hook can bypass a HEAD injection, while this wrapper still
     * observes the one original call and records its returned acceptance value.
     */
    @WrapMethod(method = "doBlockEvent(Lnet/minecraft/world/level/BlockEventData;)Z")
    private boolean phoenium$dispatch(BlockEventData event, Operation<Boolean> original) {
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
        boolean accepted = original.call(event);
        if (Stage4Trace.fixture(event.pos()))
            Stage4Trace.event("block.dispatch.result", "pos=" + event.pos() + " a=" + event.paramA()
                + " b=" + event.paramB() + " result=" + accepted);
        return accepted;
    }

    @Inject(method = "tickFluid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;)V", at = @At("HEAD"), require = 1)
    private void phoenium$fluid(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (Stage4Trace.fixture(pos)) Stage4Trace.event("tick.fluid", "pos=" + pos + " fluid=" + fluid + " gameTime=" + ((ServerLevel) (Object) this).getGameTime()
            + " " + Stage4Trace.authority((ServerLevel) (Object) this, pos));
        if (Stage4Trace.fixture(pos)) Stage4Trace.bodyObserved((ServerLevel) (Object) this, pos, "fluid.tick");
    }

    @WrapMethod(method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V")
    private void phoenium$block(BlockPos pos, Block block, Operation<Void> original) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (Stage4Trace.fixture(pos)) Stage4Trace.event("tick.block.vanilla", "pos=" + pos + " block=" + Stage4Trace.block(block)
            + " gameTime=" + level.getGameTime() + " " + Stage4Trace.authority(level, pos));
        original.call(pos, block);
        // Record body ownership and the reload effect only after the original callback returns
        // successfully.  The fixture never changes native state from this observation path.
        if (Stage4Trace.fixture(pos)) Stage4Trace.bodyObserved(level, pos, "block.tick");
        if (Stage4Trace.reloadEffect(pos) && block == Blocks.STONE_BUTTON) {
            var state = level.getBlockState(pos);
            boolean reached = state.is(Blocks.STONE_BUTTON)
                && !state.getValue(ButtonBlock.POWERED);
            if (reached) {
                Stage4Trace.event("effect.tick.reached", "pos=" + pos + " gameTime=" + level.getGameTime()
                    + " state=" + state);
                System.out.println("STAGE4_TRACE_RELOAD_EFFECT reached=true gameTime=" + level.getGameTime());
            }
        }
    }
}
