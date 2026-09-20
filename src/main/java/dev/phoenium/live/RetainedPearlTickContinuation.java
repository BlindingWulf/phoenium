package dev.phoenium.live;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.HitResult;

/** One exact, single-consumption suffix of the pinned 26.2 native pearl tick. */
final class RetainedPearlTickContinuation {
    private final CrossDimensionReplacementBoundary.Token token;
    private final ThrownEnderpearl source;
    private final ServerLevel sourceLevel;
    private final PortalProcessor processor;
    private final Portal portal;
    private final BlockPos entryPosition;
    private final HitResult hitResult;
    private final int previousSectionX;
    private final int previousSectionZ;
    private final Entity retainedOwner;
    private boolean consumed;
    private boolean retired;

    RetainedPearlTickContinuation(CrossDimensionReplacementBoundary.Token token, ThrownEnderpearl source,
            ServerLevel sourceLevel, PortalProcessor processor, Portal portal, BlockPos entryPosition,
            HitResult hitResult, int previousSectionX, int previousSectionZ, Entity retainedOwner) {
        this.token = Objects.requireNonNull(token, "token");
        this.source = Objects.requireNonNull(source, "source");
        this.sourceLevel = Objects.requireNonNull(sourceLevel, "sourceLevel");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.portal = Objects.requireNonNull(portal, "portal");
        this.entryPosition = Objects.requireNonNull(entryPosition, "entryPosition").immutable();
        this.hitResult = Objects.requireNonNull(hitResult, "hitResult");
        this.previousSectionX = previousSectionX;
        this.previousSectionZ = previousSectionZ;
        this.retainedOwner = retainedOwner;
    }

    synchronized void resume() {
        if (consumed || retired) throw new IllegalStateException("pearl portal continuation is not resumable");
        consumed = true;
        ((PearlPortalNativeAccess.EntitySuffix) source).phoenium$resumePearlPortalBaseTickSuffix();
        ((PearlPortalNativeAccess.ProjectileSuffix) source).phoenium$resumePearlPortalProjectileTickSuffix();
        ((PearlPortalNativeAccess.ThrowableSuffix) source).phoenium$resumePearlPortalThrowableTickSuffix(hitResult);
        ((PearlPortalNativeAccess.PearlSuffix) source).phoenium$resumePearlPortalPearlTickSuffix(
            previousSectionX, previousSectionZ, retainedOwner);
    }

    synchronized void retire() { retired = true; }
    synchronized boolean consumed() { return consumed; }
    CrossDimensionReplacementBoundary.Token token() { return token; }
    ThrownEnderpearl source() { return source; }
    ServerLevel sourceLevel() { return sourceLevel; }
    PortalProcessor processor() { return processor; }
    Portal portal() { return portal; }
    BlockPos entryPosition() { return entryPosition; }
}
