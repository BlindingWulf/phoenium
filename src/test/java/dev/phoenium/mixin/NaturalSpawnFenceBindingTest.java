package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import java.util.Arrays;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.gen.Invoker;
import static org.junit.jupiter.api.Assertions.*;

class NaturalSpawnFenceBindingTest {
    static ClassNode node(Class<?> type) throws Exception {
        var node = new ClassNode();
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }
    @Test void nativeFutureSelectorHasNoHiddenManagedWaitAndInvokerMatchesExactly() throws Exception {
        var hook = Arrays.stream(RegionalServerChunkCacheAccess.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("phoenium$naturalSpawnFuture")).findFirst().orElseThrow();
        String target = hook.getAnnotation(Invoker.class).value();
        var method = node(ServerChunkCache.class).methods.stream()
            .filter(value -> value.name.equals(target) && value.desc.equals(Type.getMethodDescriptor(hook))).findFirst().orElseThrow();
        assertFalse(Arrays.stream(method.instructions.toArray()).anyMatch(insn -> insn instanceof MethodInsnNode call
            && (call.name.equals("managedBlock") || call.name.equals("join"))));
    }
    @Test void allRetainedNativeHelperInvokersMatchPinnedDescriptors() throws Exception {
        var target = node(NaturalSpawner.class);
        for (var hook : NaturalSpawnContinuationAccess.class.getDeclaredMethods()) {
            var annotation = hook.getAnnotation(Invoker.class); assertNotNull(annotation);
            assertTrue(target.methods.stream().anyMatch(value -> value.name.equals(annotation.value())
                && value.desc.equals(Type.getMethodDescriptor(hook))), hook.toString());
        }
    }
    @Test void captureBindsTheDeclaredNativeEntryAndPublishedWorldStructureRuntime() throws Exception {
        var hook = Arrays.stream(RegionalNaturalSpawnerMixin.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("phoenium$retainQueries")).findFirst().orElseThrow();
        assertArrayEquals(new String[] {"spawnForChunk"}, hook.getAnnotation(WrapMethod.class).method());
        var nativeEntry = node(NaturalSpawner.class).methods.stream().filter(method -> method.name.equals("spawnForChunk")).findFirst().orElseThrow();
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V", nativeEntry.desc);
        var binding = node(FullProductionMapMixin.class).methods.stream().filter(method -> method.name.equals("phoenium$naturalSpawnStructures")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(binding.instructions.toArray()).anyMatch(insn -> insn instanceof MethodInsnNode call
            && call.owner.equals("dev/phoenium/live/PhoeniumLiveRuntime") && call.name.equals("naturalSpawnStructures")));
        var publication = node(FullFinishingWorldContextMixin.class).methods.stream().filter(method -> method.name.equals("phoenium$world")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(publication.instructions.toArray()).anyMatch(insn -> insn instanceof MethodInsnNode call && call.name.equals("naturalSpawnStructures")));
    }
    @Test void nativeSpawningChunkHasNoMutationSuffixAfterTheCapturedSpawnCall() throws Exception {
        var method = node(ServerChunkCache.class).methods.stream().filter(value -> value.name.equals("tickSpawningChunk")).findFirst().orElseThrow();
        var call = Arrays.stream(method.instructions.toArray()).filter(insn -> insn instanceof MethodInsnNode invoke
            && invoke.owner.equals("net/minecraft/world/level/NaturalSpawner") && invoke.name.equals("spawnForChunk")).findFirst().orElseThrow();
        for (var next = call.getNext(); next != null; next = next.getNext()) {
            assertFalse(next instanceof MethodInsnNode || next instanceof FieldInsnNode,
                "new native caller suffix requires an explicit continuation, not early callback completion");
        }
    }
    @Test void pollHookWrapsTheWholeNativeMethodAndRngGetterReturnsPersistentField() throws Exception {
        var wrap = Arrays.stream(ChunkStagePumpMixin.class.getDeclaredMethods()).filter(method -> method.getName().equals("phoenium$nativePollReturn"))
            .findFirst().orElseThrow().getAnnotation(WrapMethod.class);
        assertArrayEquals(new String[] {"pollTask()Z"}, wrap.method());
        var getter = node(Level.class).methods.stream().filter(method -> method.name.equals("getRandom") && method.desc.equals("()Lnet/minecraft/util/RandomSource;"))
            .findFirst().orElseThrow();
        assertEquals(1, Arrays.stream(getter.instructions.toArray()).filter(insn -> insn instanceof FieldInsnNode field
            && field.name.equals("random") && field.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD).count());
        assertEquals(0, Arrays.stream(getter.instructions.toArray()).filter(insn -> insn instanceof MethodInsnNode).count());
    }
}
