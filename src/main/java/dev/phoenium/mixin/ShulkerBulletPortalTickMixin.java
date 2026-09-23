package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import dev.phoenium.live.ShulkerBulletPortalNativeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact source continuations at the inherited and direct 26.2 ShulkerBullet portal calls. */
@Mixin(ShulkerBullet.class)
abstract class ShulkerBulletPortalTickMixin extends Projectile implements ShulkerBulletPortalNativeAccess {
    protected ShulkerBulletPortalTickMixin(EntityType<? extends Projectile> type, Level level) { super(type, level); }

    @Shadow private @Nullable EntityReference<Entity> finalTarget;
    @Shadow private @Nullable Direction currentMoveDirection;
    @Shadow private int flightSteps;
    @Shadow private double targetDeltaX;
    @Shadow private double targetDeltaY;
    @Shadow private double targetDeltaZ;
    @Shadow private void selectNextMoveDirection(Direction.Axis avoidAxis, Entity target) { }

    @WrapMethod(method = "tick()V")
    private void phoenium$retainShulkerBulletTick(Operation<Void> original) {
        ShulkerBullet self = (ShulkerBullet) (Object) this;
        if (Phoenium.liveRuntime().shulkerBulletPortalSuspended(self)) return;
        Phoenium.liveRuntime().beginShulkerBulletPortalTick(self);
        try {
            original.call();
            Phoenium.liveRuntime().completeShulkerBulletPortalTick(self);
        } finally {
            Phoenium.liveRuntime().finishShulkerBulletPortalTick(self);
        }
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/Projectile;tick()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedInheritedTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().shulkerBulletPortalSuspended((ShulkerBullet) (Object) this)) ci.cancel();
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/ShulkerBullet;handlePortal()V"),
        require = 1, expect = 1)
    private void phoenium$retainDirectPortalLocals(CallbackInfo ci,
            @Local(index = 1) Entity target, @Local(index = 2) HitResult hit,
            @Local(index = 3) Vec3 movement) {
        Phoenium.liveRuntime().retainShulkerBulletDirectLocals(
            (ShulkerBullet) (Object) this, target, hit, movement);
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/ShulkerBullet;handlePortal()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedDirectTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().shulkerBulletPortalSuspended((ShulkerBullet) (Object) this)) ci.cancel();
    }

    @Override public void phoenium$resumeShulkerAfterProjectileTick() {
        Entity target = !this.level().isClientSide() ? EntityReference.getEntity(this.finalTarget, this.level()) : null;
        HitResult hit = null;
        if (!this.level().isClientSide()) {
            if (target == null) this.finalTarget = null;
            if (target == null || !target.isAlive() || target instanceof Player player && player.isSpectator()) {
                this.applyGravity();
            } else {
                this.targetDeltaX = Mth.clamp(this.targetDeltaX * 1.025, -1.0, 1.0);
                this.targetDeltaY = Mth.clamp(this.targetDeltaY * 1.025, -1.0, 1.0);
                this.targetDeltaZ = Mth.clamp(this.targetDeltaZ * 1.025, -1.0, 1.0);
                Vec3 steering = this.getDeltaMovement();
                this.setDeltaMovement(steering.add((this.targetDeltaX - steering.x) * 0.2,
                    (this.targetDeltaY - steering.y) * 0.2, (this.targetDeltaZ - steering.z) * 0.2));
            }
            hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        }
        Vec3 movement = this.getDeltaMovement();
        this.setPos(this.position().add(movement));
        this.applyEffectsFromBlocks();
        if (this.portalProcess != null && this.portalProcess.isInsidePortalThisTick()) this.handlePortal();
        phoenium$resumeShulkerDirectPortalSuffix(target, hit, movement);
    }

    @Override public void phoenium$resumeShulkerDirectPortalSuffix(Entity target, HitResult hit, Vec3 movement) {
        if (hit != null && this.isAlive() && hit.getType() != HitResult.Type.MISS)
            this.hitTargetOrDeflectSelf(hit);
        ProjectileUtil.rotateTowardsMovement(this, 0.5F);
        if (this.level().isClientSide()) {
            this.level().addParticle(ParticleTypes.END_ROD, this.getX() - movement.x,
                this.getY() - movement.y + 0.15, this.getZ() - movement.z, 0.0, 0.0, 0.0);
        } else if (target != null) {
            if (this.flightSteps > 0) {
                this.flightSteps--;
                if (this.flightSteps == 0)
                    this.selectNextMoveDirection(this.currentMoveDirection == null
                        ? null : this.currentMoveDirection.getAxis(), target);
            }
            if (this.currentMoveDirection != null) {
                BlockPos current = this.blockPosition();
                Direction.Axis axis = this.currentMoveDirection.getAxis();
                if (this.level().loadedAndEntityCanStandOn(current.relative(this.currentMoveDirection), this)) {
                    this.selectNextMoveDirection(axis, target);
                } else {
                    BlockPos targetPos = target.blockPosition();
                    if (axis == Direction.Axis.X && current.getX() == targetPos.getX()
                            || axis == Direction.Axis.Z && current.getZ() == targetPos.getZ()
                            || axis == Direction.Axis.Y && current.getY() == targetPos.getY())
                        this.selectNextMoveDirection(axis, target);
                }
            }
        }
    }
}
