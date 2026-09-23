package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.Phoenium;
import dev.phoenium.live.BoatPortalNativeAccess;
import java.util.List;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact 26.2 AbstractBoat.tick continuation after its inherited Entity.tick call. */
@Mixin(AbstractBoat.class)
abstract class BoatPortalTickMixin extends VehicleEntity implements BoatPortalNativeAccess {
    @Shadow @Final private float[] paddlePositions;
    @Shadow @Final private InterpolationHandler interpolation;
    @Shadow private void floatBoat() { throw new AssertionError(); }
    @Shadow private void controlBoat() { throw new AssertionError(); }
    @Shadow private void tickBubbleColumn() { throw new AssertionError(); }
    @Shadow protected abstract SoundEvent getPaddleSound();
    @Shadow protected abstract int getMaxPassengers();
    @Shadow public abstract boolean hasEnoughSpaceFor(Entity entity);

    protected BoatPortalTickMixin(EntityType<?> type, Level level) { super(type, level); }

    @WrapMethod(method = "tick()V")
    private void phoenium$retainBoatTick(Operation<Void> original) {
        AbstractBoat self = (AbstractBoat) (Object) this;
        if (Phoenium.liveRuntime().boatPortalSuspended(self)) return;
        Phoenium.liveRuntime().beginBoatPortalTick(self);
        try { original.call(); }
        finally { Phoenium.liveRuntime().finishBoatPortalTick(self); }
    }

    // AbstractBoat's pinned bytecode invokespecial VehicleEntity.tick()V at offset 116.
    @Inject(method = "tick()V", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/entity/vehicle/VehicleEntity;tick()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedBoatTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().completeBoatPortalTick((AbstractBoat) (Object) this)) ci.cancel();
    }

    /** Pinned AbstractBoat.java:228-287, replayed on the original source under its owner. */
    @Override public void phoenium$resumeBoatTickSuffix() {
        AbstractBoat self = (AbstractBoat) (Object) this;
        this.interpolation.interpolate();
        if (this.isLocalInstanceAuthoritative()) {
            if (!(this.getFirstPassenger() instanceof Player)) self.setPaddleState(false, false);
            this.floatBoat();
            if (this.level().isClientSide()) {
                this.controlBoat();
                this.level().sendPacketToServer(new ServerboundPaddleBoatPacket(
                    self.getPaddleState(0), self.getPaddleState(1)));
            }
            this.move(MoverType.SELF, this.getDeltaMovement());
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }
        this.applyEffectsFromBlocks();
        this.applyEffectsFromBlocks();
        this.tickBubbleColumn();

        for (int i = 0; i <= 1; i++) {
            if (self.getPaddleState(i)) {
                if (!this.isSilent()
                        && this.paddlePositions[i] % (float) (Math.PI * 2) <= (float) (Math.PI / 4)
                        && (this.paddlePositions[i] + (float) (Math.PI / 8)) % (float) (Math.PI * 2)
                            >= (float) (Math.PI / 4)) {
                    SoundEvent sound = this.getPaddleSound();
                    if (sound != null) {
                        Vec3 viewVector = this.getViewVector(1.0F);
                        double dx = i == 1 ? -viewVector.z : viewVector.z;
                        double dz = i == 1 ? viewVector.x : -viewVector.x;
                        this.level().playSound(null, this.getX() + dx, this.getY(), this.getZ() + dz,
                            sound, this.getSoundSource(), 1.0F, 0.8F + 0.4F * this.random.nextFloat());
                    }
                }
                this.paddlePositions[i] += (float) (Math.PI / 8);
            } else {
                this.paddlePositions[i] = 0.0F;
            }
        }

        List<Entity> entities = this.level().getEntities(this,
            this.getBoundingBox().inflate(0.2F, -0.01F, 0.2F), EntitySelector.pushableBy(this));
        if (!entities.isEmpty()) {
            boolean addNewPassengers = !this.level().isClientSide()
                && !(this.getControllingPassenger() instanceof Player);
            for (Entity entity : entities) {
                if (!entity.hasPassenger(this)) {
                    if (addNewPassengers && this.getPassengers().size() < this.getMaxPassengers()
                            && !entity.isPassenger() && this.hasEnoughSpaceFor(entity)
                            && entity instanceof LivingEntity
                            && !entity.is(EntityTypeTags.CANNOT_BE_PUSHED_ONTO_BOATS)) {
                        entity.startRiding(this);
                    } else {
                        this.push(entity);
                    }
                }
            }
        }
    }
}
