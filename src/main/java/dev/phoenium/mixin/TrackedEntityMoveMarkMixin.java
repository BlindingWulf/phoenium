package dev.phoenium.mixin;

import dev.phoenium.service.TrackingMoveMarks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** S9-D5: the move-plan mark read by ChunkMap.move's updatePlayer callback (see {@link TrackingMoveMarks}). */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMoveMarkMixin implements TrackingMoveMarks.Tracker {
    @Unique private Object phoenium$moveMark;
    @Override public Object phoenium$moveMark() { return phoenium$moveMark; }
    @Override public void phoenium$moveMark(Object plan) { phoenium$moveMark = plan; }
}
