package dev.phoenium.adversestop.mixin;

import dev.phoenium.adversestop.AdverseStopProbe;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
abstract class AdverseChunkMapMixin {
    @Inject(method = "close()V", at = @At("HEAD"), require = 1)
    private void phoenium$enter(CallbackInfo ci) { AdverseStopProbe.mapCloseEntered(this); }

    @Inject(method = "close()V", at = @At("RETURN"), require = 1)
    private void phoenium$closed(CallbackInfo ci) { AdverseStopProbe.mapClosed(this); }
}
