package dev.phoenium.adversestop.mixin;

import dev.phoenium.adversestop.AdverseStopProbe;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class AdverseServerLevelMixin {
    @Inject(method = "close()V", at = @At("RETURN"), require = 1)
    private void phoenium$closed(CallbackInfo ci) { AdverseStopProbe.levelClosed(this); }
}
