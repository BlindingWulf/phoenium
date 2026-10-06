package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

/** Bedrock FD29 regression: a boat attack removes the boat inside ChunkMap.removeEntity's owner-free
 * drain, which executes the player's already-queued move against the half-removed tracker. */
final class RegionalTrackingRemovalInterleaveTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static <T> T bare(Class<T> type) throws Exception {
        Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) unsafe.get(null)).allocateInstance(type));
    }

    private static void markRemoved(Entity entity) throws Exception {
        Field reason = Entity.class.getDeclaredField("removalReason");
        reason.setAccessible(true);
        reason.set(entity, Entity.RemovalReason.KILLED);
    }

    @Test
    void removedTrackerIsExcludedFromAnInterleavedMoveButLiveAndSelfAreNot() throws Exception {
        ServerPlayer mover = bare(ServerPlayer.class);
        Entity boat = bare(net.minecraft.world.entity.vehicle.boat.Boat.class);
        assertFalse(RegionalTrackingService.removalInProgress(boat, mover), "live tracker stays selectable");
        markRemoved(boat);
        assertTrue(RegionalTrackingService.removalInProgress(boat, mover), "removed tracker must be skipped");
        assertFalse(RegionalTrackingService.removalInProgress(mover, mover), "the mover's own entry is never skipped");
    }
}
