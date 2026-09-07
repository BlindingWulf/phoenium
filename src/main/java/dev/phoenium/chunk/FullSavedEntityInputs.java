package dev.phoenium.chunk;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;

/** Pinned 26.2 constructor/reader census. This selects requirements; it never runs a reader.
 *
 * These concrete saved readers restore their newly allocated entity, registry-backed values,
 * inventory and brain state. Entity.firstTick prevents LivingEntity dimension/sleep changes
 * from running collision repair. Goal/brain construction does not tick its behaviors. Shared
 * scoreboard, entity references, raids, effects, sounds and tracking remain separate service
 * obligations at execution. Villager schedules, tadpole conversion and resolved display text
 * have dedicated contributors and are deliberately absent from this set.
 */
final class FullSavedEntityInputs {
    private static final Set<String> OWN_STATE_READERS = Set.of(("""
            acacia_boat acacia_chest_boat allay area_effect_cloud armadillo armor_stand arrow axolotl
            bamboo_chest_raft bamboo_raft bat bee birch_boat birch_chest_boat blaze block_display
            bogged breeze breeze_wind_charge camel camel_husk cat cave_spider cherry_boat
            cherry_chest_boat chest_minecart chicken cod copper_golem command_block_minecart
            cow creaking creeper dark_oak_boat dark_oak_chest_boat dolphin donkey dragon_fireball
            drowned egg elder_guardian enderman endermite ender_dragon ender_pearl end_crystal
            evoker evoker_fangs experience_bottle experience_orb eye_of_ender falling_block
            fireball firework_rocket fox frog furnace_minecart ghast happy_ghast giant
            glow_item_frame glow_squid goat guardian hoglin hopper_minecart horse husk
            illusioner interaction iron_golem item item_display item_frame jungle_boat
            jungle_chest_boat leash_knot lightning_bolt llama llama_spit magma_cube
            mangrove_boat mangrove_chest_boat mannequin marker minecart mooshroom mule
            nautilus oak_boat oak_chest_boat ocelot ominous_item_spawner painting pale_oak_boat
            pale_oak_chest_boat panda parched parrot phantom pig piglin piglin_brute pillager
            polar_bear splash_potion lingering_potion pufferfish rabbit ravager salmon sheep
            shulker shulker_bullet silverfish skeleton skeleton_horse slime small_fireball
            sniffer snowball snow_golem spawner_minecart spectral_arrow spider spruce_boat
            spruce_chest_boat squid stray strider sulfur_cube tnt tnt_minecart trader_llama
            trident tropical_fish turtle vex vindicator wandering_trader warden wind_charge
            witch wither wither_skeleton wither_skull wolf zoglin zombie zombie_horse
            zombie_nautilus zombie_villager zombified_piglin player fishing_bobber
            """).strip().split("\\s+"));

    private FullSavedEntityInputs() { }

    static boolean supports(FullSavedInput.EntityFact fact) {
        // Explicit pinned IDs make a new or modded reader an actual missing contributor.
        return fact.type().startsWith("minecraft:") && OWN_STATE_READERS.contains(fact.type().substring(10));
    }

    /** SpawnContext.create uses BiomeManager.getBiome(BlockPos), whose fuzzy lookup picks
     * one of eight quart corners. Record both horizontal candidates without reading a seed,
     * selecting a variant, evaluating its predicate or consuming gameplay random values.
     */
    static Set<ChunkPos> conversionBiomePositions(FullSavedInput.EntityFact fact) {
        int quartX = (((int) Math.floor(fact.x())) - 2) >> 2;
        int quartZ = (((int) Math.floor(fact.z())) - 2) >> 2;
        Set<ChunkPos> positions = new LinkedHashSet<>();
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++)
            positions.add(new ChunkPos((quartX + x) >> 2, (quartZ + z) >> 2));
        return Collections.unmodifiableSet(positions);
    }
}
