package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.live.PortalDestinationObservation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.EndPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep vanilla platform creation at its original call site while admitting its
 * reads, block writes and resource drops under the destination service. */
@Mixin(EndPortalBlock.class)
abstract class EndPortalPlatformMixin {
    @WrapOperation(method="getPortalDestination",at=@At(value="INVOKE",target=
        "Lnet/minecraft/world/level/levelgen/feature/EndPlatformFeature;createEndPlatform(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Z)V"),
        require=1,allow=1)
    private void phoenium$destinationPlatform(ServerLevelAccessor destination,BlockPos origin,boolean dropResources,
            Operation<Void> original,ServerLevel currentLevel,Entity entity,BlockPos entry) {
        PortalDestinationObservation.endPlatform(entity,(ServerLevel)destination,origin,
            () -> original.call(destination,origin,dropResources));
    }
}
