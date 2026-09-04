package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Binding stop witness, not FUT acceptance: accepted light input is produced inside read. */
class ChunkReloadPrerequisiteOrderTest {
    @Test void realVanillaReadContainsIngressAfterPoiConsistency() throws IOException {
        List<String> readCalls = calls(SerializableChunkData.class, "read");
        String poi = "net/minecraft/world/entity/ai/village/poi/PoiManager.checkConsistencyWithBlocks";
        String retain = "net/minecraft/world/level/lighting/LevelLightEngine.retainData";
        String queue = "net/minecraft/world/level/lighting/LevelLightEngine.queueSectionData";
        assertEquals(1, readCalls.stream().filter(poi::equals).count());
        assertEquals(1, readCalls.stream().filter(retain::equals).count());
        assertEquals(2, readCalls.stream().filter(queue::equals).count());
        assertTrue(readCalls.indexOf(poi) < readCalls.indexOf(retain));
        assertTrue(readCalls.indexOf(retain) < readCalls.indexOf(queue));
        List<String> applyCalls = calls(ChunkMap.class, "lambda$scheduleChunkLoad$3");
        assertTrue(applyCalls.contains("net/minecraft/world/level/chunk/storage/SerializableChunkData.read"));
        for (String method : List.of("scheduleChunkLoad", "lambda$scheduleChunkLoad$0", "lambda$scheduleChunkLoad$1", "lambda$scheduleChunkLoad$2")) {
            assertFalse(calls(ChunkMap.class, method).contains(retain));
            assertFalse(calls(ChunkMap.class, method).contains(queue));
        }
    }

    @Test void preparedLightBatchOpensReloadGateWithoutAnAcceptedReceipt() {
        var world = new WorldId("reload-order"); var target = new RoutingKey(world, 1, 2);
        var services = new ChunkPrerequisiteServices(world, "reload-order");
        var lifecycle = services.lifecycles().activateHolder(0, target, new Object());
        var work = services.works().beginOrResolveSharedWork(lifecycle, new ChunkWorkRegistry.WorkReservation("load","parsed",0));
        var poi = new PoiInstallService.InstallId(lifecycle, work, "all", 0);
        var stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.PARSED_APPLY, "reload", 0);
        var light = new LightInputService.BatchId(lifecycle, work, stage, 0);
        var block = new LightInputService.SectionInput(LightInputService.Layer.BLOCK, "0", 0);
        // Deliberately satisfy POI in the ledger to isolate the light prerequisite.
        assertEquals(PoiInstallService.Claim.WON, services.poi().claim(poi));
        assertEquals(PoiInstallService.Settlement.INSTALLED, services.poi().installed(poi));
        assertTrue(services.light().register(light, true, List.of(block)));
        assertTrue(services.light().receipt(light).isEmpty());
        var required = LoadApplyPrerequisites.Required.reload(poi, light);
        assertEquals(LoadApplyPrerequisites.State.READY, services.prerequisites().evaluate(lifecycle, work, required));
        assertTrue(services.light().receipt(light).isEmpty(), "registration still cannot certify ingress");
        services.shutdown();
    }

    private static List<String> calls(Class<?> type, String method) throws IOException {
        var result = new ArrayList<String>();
        int[] matches = {0};
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals(method)) return null;
                    matches[0]++;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            result.add(owner + "." + name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(1, matches[0], "exact vanilla method " + type.getName() + "." + method);
        return result;
    }
}
