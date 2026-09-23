package dev.phoenium.live;

import net.minecraft.world.phys.Vec3;

/** Source-owned continuations after the pinned AbstractMinecart direct portal call. */
public interface MinecartPortalNativeAccess extends AbstractMinecartPortalNativeAccess {
    void phoenium$resumeMinecartPortalSuffix(double lastKnownYRot, Vec3 lastKnownPos);
}
