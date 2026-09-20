package dev.phoenium.live;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.HitResult;

/** Typed native suffix seams for the pinned Minecraft 26.2 pearl tick. */
public final class PearlPortalNativeAccess {
    private PearlPortalNativeAccess() { }

    public interface EntitySuffix {
        void phoenium$resumePearlPortalBaseTickSuffix();
        void phoenium$removeAfterChangingDimensions();
        void phoenium$teleportSpectators(TeleportTransition transition, ServerLevel oldLevel);
    }
    public interface ProjectileSuffix { void phoenium$resumePearlPortalProjectileTickSuffix(); }
    public interface ThrowableSuffix { void phoenium$resumePearlPortalThrowableTickSuffix(HitResult result); }
    public interface PearlSuffix {
        void phoenium$resumePearlPortalPearlTickSuffix(int previousSectionX, int previousSectionZ, Entity retainedOwner);
    }
}
