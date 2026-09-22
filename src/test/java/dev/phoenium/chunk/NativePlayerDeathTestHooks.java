package dev.phoenium.chunk;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;

/** Same native tickDeath call seam; the fixture supplies its serial service endpoint. */
public final class NativePlayerDeathTestHooks {
    public static void remove(LivingEntity entity,Entity.RemovalReason reason) {
        if(entity instanceof ServerPlayer player&&reason==Entity.RemovalReason.KILLED) {
            try(var turn=FullFinishingServices.forServer(player.level().getServer()).referenceAccess()) {
                PlayerRespawnHandoff.captureDeathPosition(player);
                entity.remove(reason);PlayerRespawnHandoff.recordDeathRemovalCompleted(player);
            }
        } else entity.remove(reason);
    }
}
