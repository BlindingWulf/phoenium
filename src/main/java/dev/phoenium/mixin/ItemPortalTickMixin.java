package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.Phoenium;
import dev.phoenium.live.ItemPortalTransitionManager;
import dev.phoenium.live.NativePortalTransfer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
abstract class ItemPortalTickMixin extends Entity implements ItemPortalTransitionManager.ItemSuffix {
    @Shadow private int age;
    @Shadow private int pickupDelay;
    @Shadow private void setUnderwaterMovement() { throw new AssertionError(); }
    @Shadow private void setUnderLavaMovement() { throw new AssertionError(); }
    @Shadow private boolean isMergable() { throw new AssertionError(); }
    @Shadow private void mergeWithNeighbours() { throw new AssertionError(); }
    protected ItemPortalTickMixin(EntityType<?> type, Level level) { super(type, level); }

    @WrapMethod(method = "tick()V")
    private void phoenium$itemTick(Operation<Void> original) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (Phoenium.liveRuntime().itemPortalSuspended(self)) return;
        Phoenium.liveRuntime().beginItemPortalTick(self);
        try { original.call(); } finally { Phoenium.liveRuntime().finishItemPortalTick(self); }
    }
    @Inject(method = "tick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void phoenium$returnItemTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().completeItemPortalTick((ItemEntity) (Object) this)) ci.cancel();
    }
    @WrapOperation(method = "teleport", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;mergeWithNeighbours()V"), require = 1, allow = 1)
    private void phoenium$mergeReturnedItem(ItemEntity item, Operation<Void> original) {
        NativePortalTransfer.destinationEffect(item, "ITEM_TELEPORT_SUFFIX", () -> original.call(item));
    }

    /** Exact 26.2 ItemEntity.tick suffix after Entity.tick, on the ORIGINAL source object. */
    @Override public void phoenium$resumeItemTickSuffix() {
        if (this.pickupDelay > 0 && this.pickupDelay != 32767) this.pickupDelay--;
        this.xo = this.getX(); this.yo = this.getY(); this.zo = this.getZ();
        Vec3 oldMovement = this.getDeltaMovement();
        if (this.isInWater() && this.getFluidHeight(FluidTags.WATER) > 0.1F) this.setUnderwaterMovement();
        else if (this.isInLava() && this.getFluidHeight(FluidTags.LAVA) > 0.1F) this.setUnderLavaMovement();
        else this.applyGravity();
        if (this.level().isClientSide()) this.noPhysics = false;
        else {
            this.noPhysics = !this.level().noCollision(this, this.getBoundingBox().deflate(1.0E-7));
            if (this.noPhysics) this.moveTowardsClosestSpace(this.getX(), (this.getBoundingBox().minY + this.getBoundingBox().maxY) / 2.0, this.getZ());
        }
        if (!this.onGround() || this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-5F || (this.tickCount + this.getId()) % 4 == 0) {
            this.move(MoverType.SELF, this.getDeltaMovement()); this.applyEffectsFromBlocks();
            float airDrag = this.getAirDrag(), groundFriction = airDrag;
            if (this.onGround()) groundFriction *= this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction();
            this.setDeltaMovement(this.getDeltaMovement().multiply(groundFriction, airDrag, groundFriction));
            if (this.onGround()) {
                Vec3 movement = this.getDeltaMovement();
                if (movement.y < 0.0) this.setDeltaMovement(movement.multiply(1.0, -0.5, 1.0));
            }
        } else this.applyEffectsFromBlocksForLastMovements();
        boolean moved = Mth.floor(this.xo) != Mth.floor(this.getX()) || Mth.floor(this.yo) != Mth.floor(this.getY()) || Mth.floor(this.zo) != Mth.floor(this.getZ());
        int rate = moved ? 2 : 40;
        if (this.tickCount % rate == 0 && !this.level().isClientSide() && this.isMergable()) this.mergeWithNeighbours();
        if (this.age != -32768) this.age++;
        this.needsSync = this.needsSync | this.updateFluidInteraction();
        if (!this.level().isClientSide()) {
            double value = this.getDeltaMovement().subtract(oldMovement).lengthSqr();
            if (value > 0.01) this.needsSync = true;
        }
        if (!this.level().isClientSide() && this.age >= 6000) this.discard();
    }
}
