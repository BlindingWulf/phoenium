package dev.phoenium.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.service.VaultBlockEntityTickContinuation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.vault.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VaultBlockEntity.Server.class)
abstract class VaultTickContinuationMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/vault/VaultBlockEntity;access$000(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"), cancellable = true, require = 1, allow = 1)
    private static void phoenium$retainSuffix(ServerLevel level, BlockPos pos, BlockState state, VaultConfig config,
            VaultServerData serverData, VaultSharedData sharedData, CallbackInfo ci, @Local(index = 7) BlockState nextState) {
        if (VaultBlockEntityTickContinuation.setChanged(level, pos, state, nextState, serverData, sharedData)) ci.cancel();
    }
}
