package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two cuts of GatewayExitBoundary. Native exit discovery blocks on chunk reads, which is illegal inside
 * a non-player entity's owner tick; the exit is prepared owner-free first.
 */
@Mixin(EndGatewayBlock.class)
abstract class EndGatewayBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
    private void phoenium$deferGatewayArming(BlockState state, Level level, BlockPos pos, Entity entity,
            InsideBlockEffectApplier effectApplier, boolean isPrecise, CallbackInfo ci) {
        if (!Phoenium.liveRuntime().gatewayMayArm(level, pos, entity)) ci.cancel();
    }

    @WrapOperation(method = "getPortalDestination", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/TheEndGatewayBlockEntity;getPortalPosition(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"),
        require = 1, allow = 1)
    private Vec3 phoenium$preparedGatewayExit(TheEndGatewayBlockEntity gateway, ServerLevel level, BlockPos pos,
            Operation<Vec3> original) {
        return Phoenium.liveRuntime().gatewayPortalPosition(gateway, level, pos, () -> original.call(gateway, level, pos));
    }
}
