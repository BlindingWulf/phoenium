package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
abstract class RegionalBlockEntityTickerMixin {
    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"))
    private void phoenium$blockEntityTicker(TickingBlockEntity ticker, CallbackInfo callback) {
        Phoenium.liveRuntime().onBlockEntityTickerAdded((Level) (Object) this, ticker);
    }
}
