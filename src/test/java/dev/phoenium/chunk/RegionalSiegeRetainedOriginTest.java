package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.mixin.RegionalSiegeSpawnerAccess;
import dev.phoenium.service.RegionalChunkGameplayService;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.gen.Accessor;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reaches the pinned original pending siege spawn selection, with terrain endpoints
 * observed but no fabricated mob, chunk, or successful spawn. */
final class RegionalSiegeRetainedOriginTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void originalPendingSiegeKeepsItsTerrainAfterPlayersMoveOrLeave() throws Exception {
        var world = new WorldId("retained-siege");
        for (int[] origin : List.of(new int[]{520, 200}, new int[]{-520, -200}, new int[]{-1, 15})) {
            VillageSiege siege = new VillageSiege();
            set(siege, VillageSiege.class, "hasSetupSiege", true);
            set(siege, VillageSiege.class, "spawnX", origin[0]);
            set(siege, VillageSiege.class, "spawnY", 65);
            set(siege, VillageSiege.class, "spawnZ", origin[1]);
            var terrain = new ArrayList<RoutingKey>();
            var random = RandomSource.create(444444);
            ServerLevel level = stub(ServerLevel.class, List.of(
                    new Answer(ServerLevel.class.getMethod("getRandom"), ignored -> random),
                    new Answer(ServerLevel.class.getMethod("players"), ignored -> {
                        fail("pending siege cannot replace its retained origin with current players"); return null;
                    }),
                    new Answer(ServerLevel.class.getMethod("getHeight", Heightmap.Types.class, int.class, int.class), args -> {
                        terrain.add(new RoutingKey(world, Math.floorDiv((int) args[1], 16), Math.floorDiv((int) args[2], 16)));
                        return 65;
                    }),
                    new Answer(ServerLevel.class.getMethod("isVillage", BlockPos.class), ignored -> false)));
            var trySpawn = VillageSiege.class.getDeclaredMethod("trySpawn", ServerLevel.class);
            trySpawn.setAccessible(true);
            trySpawn.invoke(siege, level);
            assertEquals(10, terrain.size(), "all original randomized attempts must run once");
            Set<RoutingKey> cut = cut(world, (boolean) field(siege, VillageSiege.class, "hasSetupSiege"),
                    (int) field(siege, VillageSiege.class, "spawnX"), (int) field(siege, VillageSiege.class, "spawnZ"));
            assertTrue(cut.containsAll(terrain), "every original pending terrain read needs current ownership");
            var movedPlayerOnly = new TopologyPlanner().requiredClosure(Set.of(new RoutingKey(world, 200, -200)), 3);
            assertTrue(terrain.stream().anyMatch(key -> !movedPlayerOnly.contains(key)),
                    "the old current-player-only boundary must actually miss the native path");
            assertTrue(cut.contains(new RoutingKey(world, 0, 0)));
        }
    }

    @Test void originalZombieDifficultyReadPrecedesPlacementAndOriginAccessorsMatchActualFields() throws Exception {
        for (var accessor : RegionalSiegeSpawnerAccess.class.getDeclaredMethods()) {
            String field = accessor.getAnnotation(Accessor.class).value();
            assertEquals(accessor.getReturnType(), VillageSiege.class.getDeclaredField(field).getType());
        }
        ClassNode original = new ClassNode();
        try (var bytes = VillageSiege.class.getResourceAsStream("VillageSiege.class")) {
            assertNotNull(bytes); new ClassReader(bytes).accept(original, 0);
        }
        var spawn = original.methods.stream().filter(method -> method.name.equals("trySpawn")).findFirst().orElseThrow();
        int difficulty = -1, placement = -1, position = -1;
        var instructions = spawn.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) if (instructions[i] instanceof MethodInsnNode call) {
            if (call.name.equals("blockPosition")) position = i;
            if (call.name.equals("getCurrentDifficultyAt")) difficulty = i;
            if (call.name.equals("snapTo")) placement = i;
        }
        assertTrue(position >= 0 && position < difficulty && difficulty < placement,
                "the pinned native difficulty observation is at the new entity's initial position");
        var world = new WorldId("new-siege");
        assertEquals(Set.of(new RoutingKey(world, 0, 0)), cut(world, false, 8000, -8000),
                "a discarded old origin is not terrain demand before setup; native constructor origin remains");
    }

    @SuppressWarnings("unchecked")
    private static Set<RoutingKey> cut(WorldId world, boolean setup, int x, int z) throws Exception {
        var method = RegionalChunkGameplayService.class.getDeclaredMethod("siegeFootprint", WorldId.class,
                boolean.class, int.class, int.class);
        method.setAccessible(true);
        return (Set<RoutingKey>) method.invoke(null, world, setup, x, z);
    }
}
