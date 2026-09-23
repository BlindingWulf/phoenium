package dev.phoenium.mixin;

import dev.phoenium.live.AbstractMinecartPortalNativeAccess;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Chest carts inherit AbstractMinecart.tick without a concrete outer tick tail. */
@Mixin(MinecartChest.class)
abstract class MinecartChestPortalTickMixin extends AbstractMinecartContainer
        implements AbstractMinecartPortalNativeAccess {
    protected MinecartChestPortalTickMixin(EntityType<?> type, Level level) { super(type, level); }

    @Override public void phoenium$resumeAbstractMinecartPortalSuffix() {
        this.getBehavior().tick();
        this.updateFluidInteraction();
        if (this.isInLava()) {
            this.lavaIgnite();
            this.lavaHurt();
            this.fallDistance *= 0.5;
        }
        this.firstTick = false;
    }
}
