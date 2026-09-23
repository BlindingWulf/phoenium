package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.LeaseEpoch;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.service.RegionalChunkGameplayService;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonHoldingPatternPhase;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** The native heightmap selection and the owner-free dragon entity tick cut. */
final class DragonEntityHeightmapInputTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void exactNativeNodeRingAndPodiumStayInTheEntityOwnerCut() throws Exception {
        Set<ChunkPos> inputs = new HashSet<>(inputs(BlockPos.ZERO));
        assertEquals(25, inputs.size(), "24 native node cells and the podium cell");
        assertTrue(inputs.contains(new ChunkPos(-4, 0)));
        assertTrue(inputs.contains(new ChunkPos(3, 1)));
        assertTrue(inputs.contains(new ChunkPos(0, 0)), "podium read is separate from the flight nodes");
        assertTrue(inputs.stream().allMatch(pos -> pos.x() >= -4 && pos.x() <= 3 && pos.z() >= -4 && pos.z() <= 3));
        assertFalse(inputs.contains(new ChunkPos(4, 0)), "no unbounded terrain halo");

        Set<ChunkPos> shifted = new HashSet<>(inputs(new BlockPos(512, 64, -512)));
        assertEquals(25, shifted.size());
        assertTrue(shifted.contains(new ChunkPos(32, -32)), "the native podium follows fightOrigin");
        assertTrue(shifted.contains(new ChunkPos(-4, 0)), "native flight nodes stay at absolute zero");
    }

    @Test void presentAndAbsentHeightmapInputsRetainNativeSelectionUntilOwnerTick() throws Exception {
        ChunkPos present = new ChunkPos(-4, 0), absent = new ChunkPos(3, 1);
        Object resident = new Object();
        Map<ChunkPos, Object> selected = new LinkedHashMap<>();
        selected.put(present, resident); selected.put(absent, null);
        Set<ChunkPos> nativePresent = new HashSet<>(Set.of(present));
        Map<ChunkPos, Object> now = new HashMap<>(Map.of(present, resident));
        Function<ChunkPos, Object> current = pos -> {
            assertEquals(present, pos, "absent native input must not select a FULL chunk");
            return now.get(pos);
        };
        assertTrue(current(selected, nativePresent::contains, current));
        nativePresent.add(absent);
        assertFalse(current(selected, nativePresent::contains, current),
            "newly present native input requires owner-free reselection before RNG");
        nativePresent.remove(absent);
        now.put(present, new Object());
        assertFalse(current(selected, nativePresent::contains, current),
            "a different resident chunk cannot inherit the selected receipt");
        now.put(present, resident);
        nativePresent.remove(present);
        assertFalse(current(selected, nativePresent::contains, current),
            "a retired native input requires reselection");
        nativePresent.add(present);
        assertTrue(current(selected, nativePresent::contains, current),
            "the same presence and object may continue after a stale attempt");
    }

    @Test void pinnedNativeHeightmapPathAndOwnedWaitGuardRemainExact() throws Exception {
        ClassNode dragon = original(EnderDragon.class);
        var find = dragon.methods.stream().filter(method -> method.name.equals("findClosestNode") && method.desc.equals("()I"))
            .findFirst().orElseThrow();
        assertEquals(1, List.of(find.instructions.toArray()).stream().filter(instruction ->
            instruction instanceof MethodInsnNode call && call.name.equals("getHeightmapPos")).count());
        ClassNode holding = original(DragonHoldingPatternPhase.class);
        var target = holding.methods.stream().filter(method -> method.name.equals("findNewTarget")).findFirst().orElseThrow();
        assertTrue(List.of(target.instructions.toArray()).stream().anyMatch(instruction ->
            instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/world/entity/boss/enderdragon/EnderDragon")
                && call.name.equals("findClosestNode") && call.desc.equals("()I")));

        var owner = new RegionId(new WorldId("dragon-node-test"), "owner");
        try (var ignored = OwnershipContext.enter(owner, new LeaseEpoch(1), OperationCategory.TICK)) {
            assertThrows(ChunkProtocolException.class, () -> ChunkSynchronousAccessGuard.beforeWait(() -> false));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ChunkPos> inputs(BlockPos fightOrigin) throws Exception {
        Method method = RegionalChunkGameplayService.class.getDeclaredMethod("dragonHeightmapInputChunks", BlockPos.class);
        method.setAccessible(true);
        return (List<ChunkPos>) method.invoke(null, fightOrigin);
    }

    private static boolean current(Map<ChunkPos, Object> selected, Predicate<ChunkPos> present,
                                   Function<ChunkPos, Object> current) throws Exception {
        Method method = RegionalChunkGameplayService.class.getDeclaredMethod("dragonHeightmapSelectionsCurrent",
            Map.class, Predicate.class, Function.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, selected, present, current);
    }

    private static ClassNode original(Class<?> type) throws Exception {
        ClassNode node = new ClassNode();
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(bytes); new ClassReader(bytes).accept(node, 0);
        }
        return node;
    }
}
