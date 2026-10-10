package dev.phoenium.engine;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * S9-E8b, test diagnostics only ({@code phoenium test reseed <seed>}, registered with
 * {@code -Dphoenium.test.diagnostics=true}): reseeds the vanilla randoms the engine does not seed, so that a run with fixed
 * inputs can be repeated exactly and two runs at different tile thread counts can be diffed (DET-1). Every loaded entity's
 * own random is seeded from (seed, UUID) and every level's own random from (seed, dimension). The level's random is
 * reached through {@link EngineRandom} on the server thread between ticks, i.e. its own source. Changes no game state
 * beyond those random states; a source that refuses a seed is counted, not hidden.
 */
public final class EngineTestReseed {
    private EngineTestReseed() { }

    public static String reseed(MinecraftServer server, long seed) {
        int levels = 0;
        int entities = 0;
        int refused = 0;
        for (ServerLevel level : server.getAllLevels()) {
            try {
                level.getRandom().setSeed(mix(seed, level.dimension().identifier().toString().hashCode()));
                levels++;
            } catch (RuntimeException refusedSeed) {
                refused++;
            }
            for (Entity entity : level.getAllEntities()) {
                UUID id = entity.getUUID();
                try {
                    entity.getRandom().setSeed(mix(seed ^ id.getMostSignificantBits(), id.getLeastSignificantBits()));
                    entities++;
                } catch (RuntimeException refusedSeed) {
                    refused++;
                }
            }
        }
        return "PHOENIUM_RESEED seed=" + seed + " levels=" + levels + " entities=" + entities + " refused=" + refused;
    }

    /** A 64-bit mix (SplitMix64 finaliser) of two longs, so near seeds and UUIDs give unrelated streams. */
    static long mix(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
