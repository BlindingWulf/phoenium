package dev.phoenium.engine;

import dev.phoenium.engine.mixin.EngineTrackedEntityAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * S9-E4: the entity tracker's per-player checks without the ones that cannot change anything. TrackedEntity.updatePlayer
 * pairs a player when the entity is within min(its tracking range, the player's view distance * 16) blocks horizontally
 * (and is broadcast to the player, its chunk tracked by the player), and otherwise unpairs it. An entity whose chunk is
 * more than view distance + 1 chunks from the player's on either axis is farther than view distance * 16 blocks, so for
 * it the check can only unpair, which changes nothing unless the player sees it now. Those checks are skipped; every
 * other check runs as in vanilla. Server thread only (ChunkMap's tracker methods).
 */
public final class EngineTracker {
    private EngineTracker() { }

    private static long checks;
    private static long skipped;

    /** Whether TrackedEntity.updatePlayer(player) could change anything; {@code viewDistance} is ChunkMap's for the player. */
    public static boolean mayChange(EngineTrackedEntityAccess tracker, ServerPlayer player, int viewDistance) {
        checks++;
        ChunkPos at = tracker.phoenium$entity().chunkPosition();
        ChunkPos from = player.chunkPosition();
        int reach = viewDistance + 1;
        if (Math.abs(at.x() - from.x()) <= reach && Math.abs(at.z() - from.z()) <= reach) return true;
        if (tracker.phoenium$seenBy().contains(player.connection)) return true;
        skipped++;
        return false;
    }

    static String counters() { return "trackerChecks=" + checks + " trackerSkipped=" + skipped + " " + EngineTrackerIndex.counters(); }
}
