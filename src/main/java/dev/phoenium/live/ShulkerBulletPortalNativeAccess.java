package dev.phoenium.live;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Source-owned continuation seams for the two exact 26.2 ShulkerBullet.tick call sites. */
public interface ShulkerBulletPortalNativeAccess {
    void phoenium$resumeShulkerAfterProjectileTick();
    void phoenium$resumeShulkerDirectPortalSuffix(Entity finalTarget, HitResult hitResult, Vec3 movement);
}
