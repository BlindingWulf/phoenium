package dev.phoenium.mixin;

import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractHurtingProjectile.class)
public interface HurtingProjectilePortalAccess {
    @Invoker("createParticleTrail") void phoenium$invokeCreateParticleTrail();
}
