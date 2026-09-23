package dev.phoenium.chunk;

import dev.phoenium.mixin.RegionalDragonFightAccess;
import dev.phoenium.service.RegionalChunkGameplayService;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.gen.Accessor;

import static org.junit.jupiter.api.Assertions.*;

/** Bounds the owner-free FULL input cut to the pinned native dragon fight scan paths. */
final class DragonFightScanInputTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void firstScanPreparesEveryNativePortalCellAndItsPatternBorder() throws Exception {
        Set<ChunkPos> prepared = new HashSet<>(inputs(new ChunkPos(0, 0), true));
        assertEquals(19 * 19, prepared.size());
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++)
            assertTrue(prepared.contains(new ChunkPos(x, z)), "hasActiveExitPortal cell " + x + "," + z);
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++)
            assertTrue(prepared.contains(new ChunkPos(x, z)), "findExitPortal pattern border " + x + "," + z);
        assertFalse(prepared.contains(new ChunkPos(10, 0)), "preflight must stay bounded");
    }

    @Test void ordinaryTickKeepsTheNativeArenaRowAndShiftedOriginsKeepTheLegacyCenter() throws Exception {
        Set<ChunkPos> ordinary = new HashSet<>(inputs(new ChunkPos(4, -3), false));
        assertEquals(17, ordinary.size());
        for (int x = -4; x <= 12; x++) assertTrue(ordinary.contains(new ChunkPos(x, 5)));
        assertFalse(ordinary.contains(new ChunkPos(0, 0)));

        Set<ChunkPos> scan = new HashSet<>(inputs(new ChunkPos(20, -20), true));
        assertEquals(19 * 19 + 17 * 17, scan.size());
        assertTrue(scan.contains(new ChunkPos(-8, -8)), "native legacy scan is absolute");
        assertTrue(scan.contains(new ChunkPos(8, 8)), "native legacy scan is absolute");
        assertTrue(scan.contains(new ChunkPos(11, -29)), "shifted portal pattern needs a one-chunk halo");
        assertTrue(scan.contains(new ChunkPos(29, -11)), "shifted portal pattern needs a one-chunk halo");
    }

    @Test void accessorAndPinnedNativeMethodsStillDescribeThePreparedScan() throws Exception {
        Method accessor = RegionalDragonFightAccess.class.getMethod("phoenium$needsStateScanning");
        assertEquals(boolean.class, accessor.getReturnType());
        assertEquals("needsStateScanning", accessor.getAnnotation(Accessor.class).value());
        assertEquals(boolean.class, EnderDragonFight.class.getDeclaredField("needsStateScanning").getType());

        ClassNode nativeFight = new ClassNode();
        try (var bytes = EnderDragonFight.class.getResourceAsStream("EnderDragonFight.class")) {
            assertNotNull(bytes); new ClassReader(bytes).accept(nativeFight, 0);
        }
        var activePortal = nativeFight.methods.stream().filter(method -> method.name.equals("hasActiveExitPortal"))
            .findFirst().orElseThrow();
        assertTrue(List.of(activePortal.instructions.toArray()).stream().anyMatch(instruction ->
            instruction instanceof MethodInsnNode call && call.name.equals("getChunk")
                && call.desc.equals("(II)Lnet/minecraft/world/level/chunk/LevelChunk;")),
            "legacy scan must still use synchronous FULL chunk access");
        assertTrue(List.of(activePortal.instructions.toArray()).stream().anyMatch(instruction ->
            instruction instanceof IntInsnNode constant && constant.operand == -8));
        assertTrue(List.of(activePortal.instructions.toArray()).stream().anyMatch(instruction ->
            instruction instanceof IntInsnNode constant && constant.operand == 8));
        var findPortal = nativeFight.methods.stream().filter(method -> method.name.equals("findExitPortal"))
            .findFirst().orElseThrow();
        assertTrue(List.of(findPortal.instructions.toArray()).stream().anyMatch(instruction ->
            instruction instanceof MethodInsnNode call && call.name.equals("find")
                && call.owner.equals("net/minecraft/world/level/block/state/pattern/BlockPattern")),
            "native portal pattern scan changed its declared halo");
        var patternField = EnderDragonFight.class.getDeclaredField("exitPortalPattern");
        patternField.setAccessible(true);
        var pattern = (net.minecraft.world.level.block.state.pattern.BlockPattern) patternField.get(EnderDragonFight.createDefault());
        assertEquals(7, pattern.getWidth());
        assertEquals(7, pattern.getHeight());
        assertEquals(5, pattern.getDepth());
    }

    @SuppressWarnings("unchecked")
    private static List<ChunkPos> inputs(ChunkPos origin, boolean pendingScan) throws Exception {
        var method = RegionalChunkGameplayService.class.getDeclaredMethod("dragonFightFullInputs", ChunkPos.class, boolean.class);
        method.setAccessible(true);
        return (List<ChunkPos>) method.invoke(null, origin, pendingScan);
    }
}
