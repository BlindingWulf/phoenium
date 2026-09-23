package dev.phoenium.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractArrow.class)
public interface ArrowPortalAccess {
    @Invoker("applyInertia")
    void phoenium$invokeApplyInertia(float drag);
}
