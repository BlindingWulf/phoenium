package dev.phoenium.testmixin;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

/** Exact call-site proof for the disposable adapter; production classes remain unmodified. */
final class FullRegistrationSaveBindingMixinContractTest {
    @Test
    void controlledUnloadSeamsMatchTheCurrentProductionBinding() throws Exception {
        assertCalls("unload", "(Ldev/phoenium/save/RegionalSaveCoordinator;Lnet/minecraft/server/level/ChunkMap;"
                        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ChunkHolder;"
                        + "Ljava/util/concurrent/CompletableFuture;JLjava/lang/Runnable;)V", Map.of(
                new Call("dev/phoenium/live/PhoeniumLiveRuntime", "requireChunkWorld",
                        "(Lnet/minecraft/server/level/ServerLevel;)Ldev/phoenium/chunk/ChunkBindingRuntime$WorldAuthority;"), 1,
                new Call("dev/phoenium/save/RegionalSaveBinding", "key",
                        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ldev/phoenium/topology/RoutingKey;"), 1,
                new Call("dev/phoenium/save/RegionalSaveCoordinator", "prepareSource",
                        "(Ldev/phoenium/topology/RoutingKey;Ljava/util/function/Supplier;)Ljava/lang/Object;"), 1));
        assertCalls("mutateUnload", "(Ldev/phoenium/save/RegionalSaveCoordinator;Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/lang/Runnable;)V", Map.of(
                new Call("dev/phoenium/save/RegionalSaveBinding", "key",
                        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ldev/phoenium/topology/RoutingKey;"), 1,
                new Call("dev/phoenium/save/RegionalSaveCoordinator", "unloadSource",
                        "(Ldev/phoenium/topology/RoutingKey;Ljava/lang/Runnable;)V"), 1));
        assertCalls("save", "(Ldev/phoenium/save/RegionalSaveCoordinator;Lnet/minecraft/server/level/ChunkMap;"
                        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Ljava/util/function/Supplier;)Z", Map.of(
                new Call("dev/phoenium/save/RegionalSaveBinding", "key",
                        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ldev/phoenium/topology/RoutingKey;"), 1));
    }

    @Test
    void fullRegistrationHarnessAloneInstallsTheDisposableMixin() throws Exception {
        String fabric = resource("fabric.mod.json");
        String config = resource("full-registration-probe.mixins.json");
        assertTrue(fabric.contains("full-registration-probe.mixins.json"));
        assertTrue(config.contains("dev.phoenium.testmixin"));
        assertTrue(config.contains("FullRegistrationSaveBindingMixin"));

        String production;
        try (var input = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            assertNotNull(input);
            production = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertFalse(production.contains("FullRegistrationSaveBindingMixin"));
    }

    private static String resource(String file) throws IOException {
        // Source resources are deliberately outside the ordinary test classpath; inspect their
        // exact files without copying the disposable Mixin into production resources.
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir"));
        return java.nio.file.Files.readString(root.resolve("src/testHarness/resources").resolve(file));
    }

    private static void assertCalls(String method, String descriptor, Map<Call, Integer> expected) throws IOException {
        var actual = new LinkedHashMap<Call, Integer>();
        try (var input = FullRegistrationSaveBindingMixinContractTest.class.getResourceAsStream(
                "/dev/phoenium/save/RegionalSaveBinding.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                           String signature, String[] exceptions) {
                    if (!method.equals(name) || !descriptor.equals(desc)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                             String desc, boolean isInterface) {
                            Call call = new Call(owner, name, desc);
                            if (expected.containsKey(call)) actual.merge(call, 1, Math::addExact);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(expected, actual, () -> "controlled fixture seam drift in " + method + descriptor);
    }

    private record Call(String owner, String name, String descriptor) { }
}
