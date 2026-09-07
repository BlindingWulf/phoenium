package dev.phoenium.chunk;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;

/**
 * Pinned 26.2 saved-villager initialization footprint. This consumes protected scalar
 * facts only; it never constructs a villager, decodes a brain, samples a schedule,
 * runs a reader or advances a random source.
 *
 * LivingEntity's constructor dispatches to Villager.makeBrain while position is ZERO.
 * The saved reader sets the clamped position before any saved Brain/Age refresh and
 * Villager.readAdditionalSaveData always refreshes once more. Each new brain may
 * sample its adult/baby schedule through the environment's default biome layer.
 * That layer uses getNoiseBiomeAtPosition (floor/block -> quart -> section), without
 * spatial interpolation. Both schedules therefore have the same horizontal input.
 *
 * Freshly constructed entities retain firstTick throughout the saved-reader chain.
 * Attribute, effect, age and sleeping-pose restoration cannot call the positional
 * collision repair guarded by !firstTick. Saved POI memories are only decoded and
 * copied; no sensor/behavior runs and no referenced POI/entity is looked up here.
 * Clock/environment-cache authority and the original BIOMES,false query policy are
 * separate finishing prerequisites; these positions grant neither.
 */
final class FullSavedVillagerInputs {
    private FullSavedVillagerInputs() { }

    static boolean supports(FullSavedInput.EntityFact fact) {
        return fact.type().equals("minecraft:villager");
    }

    static Set<ChunkPos> environmentPositions(FullSavedInput.EntityFact fact) {
        if (!supports(fact)) throw new IllegalArgumentException("not a saved villager fact");
        if (!Double.isFinite(fact.x()) || !Double.isFinite(fact.y()) || !Double.isFinite(fact.z()))
            throw new IllegalArgumentException(fact.path() + ": invalid saved position retains original load failure; no input closure");
        Set<ChunkPos> positions = new LinkedHashSet<>();
        positions.add(ChunkPos.ZERO);
        positions.add(new ChunkPos(((int) Math.floor(fact.x())) >> 4, ((int) Math.floor(fact.z())) >> 4));
        return Collections.unmodifiableSet(positions);
    }
}
