package dev.phoenium.chunk;

import dev.phoenium.live.GatewayExitBoundary;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Mirrors the runtime calls of EndGatewayBlockMixin; null boundary is the pre-repair native behaviour. */
public final class GatewayExitTestHooks {
    static GatewayExitBoundary boundary;
    private GatewayExitTestHooks() { }
    public static boolean mayArm(Level level, BlockPos pos, Entity entity) {
        return boundary == null || boundary.mayArm(level, pos, entity);
    }
    public static Vec3 portalPosition(TheEndGatewayBlockEntity gateway, ServerLevel level, BlockPos pos) {
        return boundary == null ? gateway.getPortalPosition(level, pos)
            : boundary.portalPosition(gateway, level, pos, () -> gateway.getPortalPosition(level, pos));
    }
}
