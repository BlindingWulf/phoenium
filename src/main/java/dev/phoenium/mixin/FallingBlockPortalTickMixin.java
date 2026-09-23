package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native prefix leaves its Block local live across handlePortal. */
@Mixin(FallingBlockEntity.class)
abstract class FallingBlockPortalTickMixin {
    @WrapMethod(method="tick()V")
    private void phoenium$fallingBlockTick(Operation<Void> original) {
        FallingBlockEntity self=(FallingBlockEntity)(Object)this;
        if (Phoenium.liveRuntime().fallingBlockPortalSuspended(self)) return;
        Phoenium.liveRuntime().beginFallingBlockPortalTick(self);
        try { original.call(); }
        finally { Phoenium.liveRuntime().finishFallingBlockPortalTick(self); }
    }
    @Inject(method="tick()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/item/FallingBlockEntity;handlePortal()V",
        shift=At.Shift.AFTER),cancellable=true,require=1,allow=1)
    private void phoenium$returnFallingBlockTick(CallbackInfo ci,@Local(ordinal=0) Block originalBlock) {
        if (Phoenium.liveRuntime().completeFallingBlockPortalTick((FallingBlockEntity)(Object)this,originalBlock)) ci.cancel();
    }
}
