package dev.phoenium.acceptance;

import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only witness for the original saved sleeping position lookup. */
@Mixin(LivingEntity.class)
abstract class FullRuntimeAcceptanceBedCheckMixin {
    @Inject(method = "lambda$checkBedExists$0(Lnet/minecraft/core/BlockPos;)Ljava/lang/Boolean;", at = @At("RETURN"))
    private void phoenium$recordSavedBedCheck(BlockPos position, CallbackInfoReturnable<Boolean> callback) {
        if (Boolean.getBoolean("phoenium.full.authoritative")) {
            FullRuntimeAcceptanceProbe.recordBedCheck((LivingEntity) (Object) this, position, callback.getReturnValue());
        }
    }
}
