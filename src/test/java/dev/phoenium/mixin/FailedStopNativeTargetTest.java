package dev.phoenium.mixin;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pinned 26.2 bytecode proof for every exact native call intercepted by failed-stop cleanup. */
final class FailedStopNativeTargetTest {
    @Test
    void minecraftServerStopTargetsAreExactAndSingular() throws Exception {
        assertCalls("net/minecraft/server/MinecraftServer", "stopServer", "()V", Map.ofEntries(
                entry("net/minecraft/network/PacketProcessor", "close", "()V", 1),
                entry("net/minecraft/server/MinecraftServer", "cancelRecordingMetrics", "()V", 1),
                entry("net/minecraft/server/network/ServerConnectionListener", "stop", "()V", 1),
                entry("net/minecraft/server/players/PlayerList", "saveAll", "()V", 1),
                entry("net/minecraft/server/players/PlayerList", "removeAll", "()V", 1),
                entry("java/util/stream/Stream", "anyMatch", "(Ljava/util/function/Predicate;)Z", 1),
                entry("net/minecraft/server/MinecraftServer", "saveAllChunks", "(ZZZ)Z", 1),
                entry("net/minecraft/server/level/ServerLevel", "close", "()V", 1),
                entry("net/minecraft/world/level/storage/SavedDataStorage", "close", "()V", 1),
                entry("net/minecraft/server/MinecraftServer$ReloadableResources", "close", "()V", 1),
                entry("net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess", "close", "()V", 1)));
    }

    @Test
    void nestedCloseTargetsMatchThePinnedNativeBodies() throws Exception {
        assertCalls("net/minecraft/server/level/ServerChunkCache", "close", "()V", Map.ofEntries(
                entry("net/minecraft/server/level/ServerChunkCache", "save", "(Z)V", 1),
                entry("net/minecraft/world/level/storage/SavedDataStorage", "close", "()V", 1),
                entry("net/minecraft/server/level/ThreadedLevelLightEngine", "close", "()V", 1),
                entry("net/minecraft/server/level/ChunkMap", "close", "()V", 1)));
        assertCalls("net/minecraft/world/level/entity/PersistentEntitySectionManager", "close", "()V", Map.ofEntries(
                entry("net/minecraft/world/level/entity/PersistentEntitySectionManager", "saveAll", "()V", 1),
                entry("net/minecraft/world/level/entity/EntityPersistentStorage", "close", "()V", 1)));
        assertCalls("net/minecraft/world/level/storage/SavedDataStorage", "close", "()V", Map.ofEntries(
                entry("net/minecraft/world/level/storage/SavedDataStorage", "saveAndJoin", "()V", 1)));
        assertCalls("net/minecraft/server/level/ServerLevel", "close", "()V", Map.ofEntries(
                entry("net/minecraft/world/level/Level", "close", "()V", 1),
                entry("net/minecraft/world/level/entity/PersistentEntitySectionManager", "close", "()V", 1)));
        assertCalls("net/minecraft/server/level/ChunkMap", "close", "()V", Map.ofEntries(
                entry("net/minecraft/server/level/ChunkTaskDispatcher", "close", "()V", 2),
                entry("net/minecraft/world/entity/ai/village/poi/PoiManager", "close", "()V", 1),
                entry("net/minecraft/world/level/chunk/storage/SimpleRegionStorage", "close", "()V", 2)));
    }

    @Test
    void productionConfigurationInstallsEveryFailureOnlyAdapter() throws Exception {
        String json;
        try (var input = FailedStopNativeTargetTest.class.getResourceAsStream("/phoenium.mixins.json")) {
            assertTrue(input != null, "production mixin configuration missing");
            json = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String mixin : new String[]{"FailedStopMinecraftServerMixin", "FailedStopServerChunkCacheMixin",
                "FailedStopPersistentEntitySectionManagerMixin", "FailedStopSavedDataStorageMixin",
                "FailedStopServerLevelMixin", "FailedStopChunkMapMixin"})
            assertTrue(json.contains('"' + mixin + '"'), () -> "missing production adapter " + mixin);
    }

    private static Map.Entry<Call, Integer> entry(String owner, String name, String descriptor, int count) {
        return Map.entry(new Call(owner, name, descriptor), count);
    }

    private static void assertCalls(String owner, String method, String descriptor,
                                    Map<Call, Integer> expected) throws IOException {
        var actual = new LinkedHashMap<Call, Integer>();
        String resource = "/" + owner + ".class";
        try (var input = FailedStopNativeTargetTest.class.getResourceAsStream(resource)) {
            assertTrue(input != null, () -> "missing native class " + resource);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                                 String[] exceptions) {
                    if (!method.equals(name) || !descriptor.equals(desc)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String invokedOwner, String invokedName,
                                                    String invokedDescriptor, boolean isInterface) {
                            Call call = new Call(invokedOwner, invokedName, invokedDescriptor);
                            if (expected.containsKey(call)) actual.merge(call, 1, Math::addExact);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(expected, actual, () -> "native invocation drift in " + owner + '.' + method + descriptor);
    }

    private record Call(String owner, String name, String descriptor) { }
}
