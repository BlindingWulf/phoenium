package dev.phoenium.acceptance;

import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
}
