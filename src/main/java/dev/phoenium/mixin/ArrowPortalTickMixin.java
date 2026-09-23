package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import dev.phoenium.live.ArrowPortalNativeAccess;
import java.util.ArrayList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suspends the direct stepMoveAndHit portal call after its source prefix. */
@Mixin(AbstractArrow.class)
abstract class ArrowPortalTickMixin extends Projectile implements ArrowPortalNativeAccess {
    protected ArrowPortalTickMixin(EntityType<? extends Projectile> type, Level level) { super(type, level); }
    @Shadow protected abstract boolean isInGround();

    @WrapMethod(method = "tick()V")
    private void phoenium$retainArrowTick(Operation<Void> original) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        if (Phoenium.liveRuntime().arrowPortalSuspended(self)) return;
        Phoenium.liveRuntime().beginArrowPortalTick(self);
        try {
            original.call();
            Phoenium.liveRuntime().completeArrowPortalTick(self);
        } finally {
            Phoenium.liveRuntime().finishArrowPortalTick(self);
        }
    }

    @Inject(method = "stepMoveAndHit(Lnet/minecraft/world/phys/BlockHitResult;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;handlePortal()V"),
        require = 1, expect = 1)
    private void phoenium$retainClearArrowStep(BlockHitResult blockHitResult, CallbackInfo ci,
            @Local(ordinal = 0) ArrayList<EntityHitResult> entitiesHit) {
        Phoenium.liveRuntime().retainArrowPortalStep((AbstractArrow) (Object) this, blockHitResult, entitiesHit);
    }

    @Inject(method = "stepMoveAndHit(Lnet/minecraft/world/phys/BlockHitResult;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;handlePortal()V",
            shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedArrowStep(BlockHitResult blockHitResult, CallbackInfo ci) {
        if (Phoenium.liveRuntime().arrowPortalSuspended((AbstractArrow) (Object) this)) ci.cancel();
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;stepMoveAndHit(Lnet/minecraft/world/phys/BlockHitResult;)V",
        shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedArrowTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().arrowPortalSuspended((AbstractArrow) (Object) this)) ci.cancel();
    }

    @Override public void phoenium$resumeArrowClearStepSuffix(boolean physicsEnabled) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        if (!self.isInWater())
            ((ArrowPortalAccess) (Object) this).phoenium$invokeApplyInertia(this.getAirDrag());
        if (physicsEnabled && !this.isInGround()) this.applyGravity();
        super.tick();
    }
}
