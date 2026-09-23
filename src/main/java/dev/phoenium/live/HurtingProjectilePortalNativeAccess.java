package dev.phoenium.live;

import net.minecraft.world.phys.HitResult;

/** Pinned 26.2 suffixes after AbstractHurtingProjectile/Projectile/Entity portal stack. */
public interface HurtingProjectilePortalNativeAccess {
    void phoenium$resumeHurtingProjectileTickSuffix(HitResult hitResult);

    interface WindChargeSuffix {
        void phoenium$resumeWindChargeTickSuffix();
    }
}
