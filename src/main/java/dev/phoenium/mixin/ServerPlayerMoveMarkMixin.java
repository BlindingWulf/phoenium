package dev.phoenium.mixin;

import dev.phoenium.service.TrackingMoveMarks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** S9-D5: the regional move plan whose original ChunkMap.move body is running for this player (see {@link TrackingMoveMarks}). */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMoveMarkMixin implements TrackingMoveMarks.Mover {
    @Unique private Object phoenium$activeMovePlan;
    @Override public Object phoenium$activeMovePlan() { return phoenium$activeMovePlan; }
    @Override public void phoenium$activeMovePlan(Object plan) { phoenium$activeMovePlan = plan; }
}
