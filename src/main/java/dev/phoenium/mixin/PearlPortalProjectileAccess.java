package dev.phoenium.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow native invoker for the retained throwable-projectile hit suffix. */
@Mixin(Projectile.class)
public interface PearlPortalProjectileAccess {
    @Invoker("hitTargetOrDeflectSelf")
    ProjectileDeflection phoenium$invokeHitTargetOrDeflectSelf(HitResult hitResult);
}
