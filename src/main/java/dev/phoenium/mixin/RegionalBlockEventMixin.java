package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class RegionalBlockEventMixin {
    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void phoenium$enqueueRegionalBlockEvent(BlockPos pos, Block block, int paramA, int paramB, CallbackInfo ci) {
        if (Phoenium.liveRuntime().enqueueRegionalBlockEvent((ServerLevel) (Object) this,
                new BlockEventData(pos.immutable(), block, paramA, paramB))) ci.cancel();
    }

    @Inject(method = "runBlockEvents", at = @At("HEAD"), cancellable = true)
    private void phoenium$runRegionalBlockEvents(CallbackInfo ci) {
        if (Phoenium.liveRuntime().runRegionalBlockEvents((ServerLevel) (Object) this)) ci.cancel();
    }
}
