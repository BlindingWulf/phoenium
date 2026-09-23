package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import dev.phoenium.live.NativePortalTransfer;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The direct portal call is the first native effect in PrimedTnt.tick. */
@Mixin(PrimedTnt.class)
abstract class TntPortalTickMixin {
    @WrapMethod(method = "tick()V")
    private void phoenium$retainTntTick(Operation<Void> original) {
        PrimedTnt self = (PrimedTnt) (Object) this;
        if (Phoenium.liveRuntime().tntPortalSuspended(self)) return;
        Phoenium.liveRuntime().beginTntPortalTick(self);
        try { original.call(); }
        finally { Phoenium.liveRuntime().finishTntPortalTick(self); }
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/item/PrimedTnt;handlePortal()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedTntTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().completeTntPortalTick((PrimedTnt) (Object) this)) ci.cancel();
    }

    /** Vanilla marks the returned replacement as portal-used after Entity.teleport returns. */
    @WrapOperation(method = "teleport", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/item/PrimedTnt;setUsedPortal(Z)V"),
        require = 1, expect = 1)
    private void phoenium$markReturnedTnt(PrimedTnt replacement, boolean usedPortal,
            Operation<Void> original) {
        NativePortalTransfer.destinationEffect(replacement, "TNT_TELEPORT_SUFFIX",
            () -> original.call(replacement, usedPortal));
    }
}
