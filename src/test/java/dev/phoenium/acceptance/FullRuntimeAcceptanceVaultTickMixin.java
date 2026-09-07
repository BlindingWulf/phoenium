package dev.phoenium.acceptance;

import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only witness for the original server vault ticker. */
@Mixin(targets = "net.minecraft.world.level.block.entity.vault.VaultBlockEntity$Server")
abstract class FullRuntimeAcceptanceVaultTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private static void phoenium$recordVaultTick(ServerLevel level, BlockPos position, BlockState state,
                                                   VaultConfig config, VaultServerData serverData,
                                                   VaultSharedData sharedData, CallbackInfo callback) {
        FullRuntimeAcceptanceProbe.recordVaultTick(level, position);
    }

    @WrapOperation(method = "tick", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/vault/VaultBlockEntity;access$000("
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)V"), require = 1, expect = 1)
    private static void phoenium$recordDirtyOutput(Level level, BlockPos position, BlockState state,
                                                    Operation<Void> original) {
        FullRuntimeAcceptanceProbe.beginVaultDirtyOutput((ServerLevel) level, position, state);
        try {
            original.call(level, position, state);
            FullRuntimeAcceptanceProbe.completeVaultDirtyOutput((ServerLevel) level, position);
        } finally {
            FullRuntimeAcceptanceProbe.endVaultDirtyOutput();
        }
    }
}
