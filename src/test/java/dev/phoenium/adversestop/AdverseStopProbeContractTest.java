package dev.phoenium.adversestop;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

/** Exact pinned-native and packaging contract for the disposable adverse probe. */
final class AdverseStopProbeContractTest {
    @Test
    void originalWriteAndSelectedSaveTargetsAreExactAndSingular() throws Exception {
        assertCalls("net/minecraft/world/level/chunk/storage/IOWorker", "runStore",
                "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/IOWorker$PendingStore;)V",
                Map.of(new Call("net/minecraft/world/level/chunk/storage/RegionFileStorage", "write",
                        "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V"), 1));
        assertCalls("net/minecraft/server/level/ChunkMap", "saveChunkIfNeeded",
                "(Lnet/minecraft/server/level/ChunkHolder;J)Z",
                Map.of(new Call("net/minecraft/server/level/ChunkMap", "save",
                        "(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z"), 1));
    }

    @Test
    void disposableConfigurationInstallsEveryWitnessAndProductionDoesNot() throws Exception {
        Path resources = Path.of(System.getProperty("user.dir"), "src", "adverseStopHarness", "resources");
        String fabric = Files.readString(resources.resolve("fabric.mod.json"));
        String config = Files.readString(resources.resolve("phoenium-adverse-stop.mixins.json"));
        assertTrue(fabric.contains("dev.phoenium.adversestop.AdverseStopProbe"));
        for (String mixin : new String[]{"AdverseChunkMapMixin", "AdverseFullServerShutdownMixin",
                "AdverseIOWorkerMixin", "AdverseLevelStorageAccessMixin", "AdverseLiveRuntimeMixin",
                "AdverseMinecraftServerMixin", "AdverseReloadableResourcesMixin",
                "AdverseSavedDataStorageMixin", "AdverseServerChunkCacheMixin", "AdverseServerLevelMixin"})
            assertTrue(config.contains('"' + mixin + '"'), () -> "missing adverse witness " + mixin);
        String production;
        try (var input = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            assertNotNull(input); production = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(production.contains("Adverse"));
    }

    @Test
    void transformedTargetsCallOnlyPublicHelperMethodsOutsideTheirMixinPackage() throws Exception {
        for (String name : new String[]{"AdverseChunkMapMixin", "AdverseFullServerShutdownMixin",
                "AdverseIOWorkerMixin", "AdverseLevelStorageAccessMixin", "AdverseLiveRuntimeMixin",
                "AdverseMinecraftServerMixin", "AdverseReloadableResourcesMixin",
                "AdverseSavedDataStorageMixin", "AdverseServerChunkCacheMixin", "AdverseServerLevelMixin"}) {
            try (var input = getClass().getResourceAsStream("/dev/phoenium/adversestop/mixin/" + name + ".class")) {
                assertNotNull(input);
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                               String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String target,
                                                                 String desc, boolean isInterface) {
                                if (!owner.equals("dev/phoenium/adversestop/AdverseStopProbe")) return;
                                try {
                                    boolean found = java.util.Arrays.stream(AdverseStopProbe.class.getMethods())
                                            .anyMatch(candidate -> candidate.getName().equals(target)
                                                    && Modifier.isPublic(candidate.getModifiers()));
                                    assertTrue(found, () -> "injected native target calls non-public probe method " + target);
                                } catch (RuntimeException failure) { throw failure; }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
    }

    @Test
    void expectedFailureIsEvidenceOnlyWithEveryNativeAndProtocolOutcome() {
        var complete = new AdverseStopProbe.Gate(true, 1, 1, 1, 1, 0, 1, 1, true, 1,
                true, true, true, true);
        assertTrue(AdverseStopProbe.accepted(complete));
        assertFalse(AdverseStopProbe.accepted(new AdverseStopProbe.Gate(false, 1, 1, 1, 1, 0, 1, 1,
                true, 1, true, true, true, true)), "an ordinary unexpected failure cannot count as PASS");
        assertFalse(AdverseStopProbe.accepted(new AdverseStopProbe.Gate(true, 1, 1, 1, 0, 1, 1, 1,
                true, 1, true, true, true, true)), "a fabricated or failed write outcome cannot count as PASS");
        assertFalse(AdverseStopProbe.accepted(new AdverseStopProbe.Gate(true, 1, 1, 1, 1, 0, 1, 1,
                false, 1, true, true, true, true)), "message equality cannot replace exception identity evidence");
        assertFalse(AdverseStopProbe.accepted(new AdverseStopProbe.Gate(true, 1, 1, 1, 1, 0, 1, 1,
                true, 1, false, true, true, true)),
                "native cleanup before write acknowledgement cannot count as PASS");
        assertFalse(AdverseStopProbe.accepted(new AdverseStopProbe.Gate(true, 1, 1, 1, 1, 0, 1, 1,
                true, 1, true, true, false, true)), "residual protocol custody cannot count as PASS");
        assertFalse(AdverseStopProbe.accepted(new AdverseStopProbe.Gate(true, 1, 1, 1, 1, 0, 1, 1,
                true, 1, true, true, true, false)), "incomplete native resource cleanup cannot count as PASS");
    }

    @Test
    void exceptionIdentityTraversalTerminatesOnCauseCycles() throws Exception {
        Throwable first = new IllegalStateException("first");
        Throwable second = new IllegalArgumentException("second");
        first.initCause(second);
        second.initCause(first);
        Throwable absent = new AssertionError("absent");
        var traversal = AdverseStopProbe.class.getDeclaredMethod(
                "containsIdentity", Throwable.class, Throwable.class);
        traversal.setAccessible(true);
        assertEquals(false, traversal.invoke(null, first, absent));
        assertEquals(true, traversal.invoke(null, first, second));
    }

    private static void assertCalls(String owner, String method, String descriptor,
                                    Map<Call, Integer> expected) throws IOException {
        var actual = new LinkedHashMap<Call, Integer>();
        try (var input = AdverseStopProbeContractTest.class.getResourceAsStream('/' + owner + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                           String signature, String[] exceptions) {
                    if (!name.equals(method) || !desc.equals(descriptor)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String calledOwner, String calledName,
                                                             String calledDescriptor, boolean isInterface) {
                            Call call = new Call(calledOwner, calledName, calledDescriptor);
                            if (expected.containsKey(call)) actual.merge(call, 1, Math::addExact);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(expected, actual, () -> "native adverse target drift in " + owner + '.' + method + descriptor);
    }

    private record Call(String owner, String name, String descriptor) { }
}
