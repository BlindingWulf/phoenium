package dev.phoenium.chunk;

import dev.phoenium.live.LiveTickPhaseBoundary;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every original call is retained at its exact side of the owner-free ticking boundary. */
final class LiveTickPhaseBoundaryTest {
    @Test void originalCallOrderAndBothBaselineMethodsRemainIntact() throws Exception {
        ClassNode level = original(ServerLevel.class), cache = original(ServerChunkCache.class);
        MethodNode levelTick = tick(level, "(Ljava/util/function/BooleanSupplier;)V");
        MethodNode cacheTick = tick(cache, "(Ljava/util/function/BooleanSupplier;Z)V");
        var originalLevelInstructions = List.of(levelTick.instructions.toArray());
        var originalCacheInstructions = List.of(cacheTick.instructions.toArray());
        List<String> expected = new ArrayList<>();
        for (String call : calls(levelTick)) {
            if (call.equals(LiveTickPhaseBoundary.CACHE + ".tick(Ljava/util/function/BooleanSupplier;Z)V")) expected.addAll(calls(cacheTick));
            else expected.add(call);
        }
        LiveTickPhaseBoundary.installLevel(level); LiveTickPhaseBoundary.installCache(cache);
        List<String> actual = new ArrayList<>();
        actual.addAll(calls(named(level, "phoenium$beforeScheduledTicks")));
        actual.addAll(calls(named(level, "phoenium$runScheduledTicks")));
        actual.addAll(calls(named(level, "phoenium$beforeChunkTick")));
        actual.addAll(calls(named(cache, "phoenium$prepareChunkTick")));
        actual.addAll(calls(named(cache, "phoenium$finishChunkTick")));
        actual.addAll(calls(named(level, "phoenium$afterChunkTick")));
        actual.addAll(calls(named(level, "phoenium$runBlockEvents")));
        actual.addAll(calls(named(level, "phoenium$finishLevelTick")));
        assertEquals(expected, actual, "Control preparation and resumed gameplay retain the original complete call order");
        assertEquals(originalLevelInstructions, List.of(levelTick.instructions.toArray()));
        assertEquals(originalCacheInstructions, List.of(cacheTick.instructions.toArray()));
        assertTrue(level.interfaces.contains("dev/phoenium/live/LiveTickPhases$LevelAccess"));
        assertTrue(cache.interfaces.contains("dev/phoenium/live/LiveTickPhases$CacheAccess"));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installLevel(level));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installCache(cache));
    }

    @Test void movedOrDuplicatedDistanceBoundaryIsRejected() throws Exception {
        ClassNode cache = original(ServerChunkCache.class);
        MethodNode tick = tick(cache, "(Ljava/util/function/BooleanSupplier;Z)V");
        MethodInsnNode selected = call(tick, "runDistanceManagerUpdates");
        tick.instructions.insertBefore(selected, selected.clone(null));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installCache(cache));
        assertTrue(cache.methods.stream().noneMatch(method -> method.name.equals("phoenium$prepareChunkTick")));
    }

    @Test void crossPhaseJumpOrHandlerCannotExposeAPartialContinuation() throws Exception {
        ClassNode level = original(ServerLevel.class); MethodNode tick = tick(level, "(Ljava/util/function/BooleanSupplier;)V");
        LabelNode end = new LabelNode(); tick.instructions.insertBefore(tick.instructions.getLast(), end);
        tick.instructions.insert(new JumpInsnNode(Opcodes.GOTO, end));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installLevel(level));
        ClassNode withHandler = original(ServerChunkCache.class); MethodNode cache = tick(withHandler, "(Ljava/util/function/BooleanSupplier;Z)V");
        var start = new LabelNode(); var finish = new LabelNode(); var handler = new LabelNode();
        cache.instructions.insert(start); cache.instructions.add(finish); cache.instructions.add(handler);
        cache.tryCatchBlocks.add(new TryCatchBlockNode(start, finish, handler, "java/lang/Throwable"));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installCache(withHandler));
    }

    @Test void addedSuffixDependencyRequiresAnExplicitCarriedLocal() throws Exception {
        ClassNode level = original(ServerLevel.class); MethodNode tick = tick(level, "(Ljava/util/function/BooleanSupplier;)V");
        tick.instructions.insertBefore(call(tick, "tickBlockEntities"), new VarInsnNode(Opcodes.ALOAD, 40));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installLevel(level));
    }

    @Test void installedFabricEventsAreRequiredAtTheirOriginalEnds() throws Exception {
        ClassNode level = original(ServerLevel.class);
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.requireInstalledLevelCallbacks(level));
        MethodNode tick = tick(level, "(Ljava/util/function/BooleanSupplier;)V");
        tick.instructions.insert(event("startLevelTick"));
        tick.instructions.insertBefore(tick.instructions.getLast(), event("endLevelTick"));
        assertDoesNotThrow(() -> LiveTickPhaseBoundary.requireInstalledLevelCallbacks(level));
        tick.instructions.insert(event("endLevelTick"));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.requireInstalledLevelCallbacks(level));
    }

    private static MethodInsnNode event(String name) {
        return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LiveTickPhaseBoundary.LEVEL,
                "handler$test$fabric-lifecycle-events-v1$" + name,
                "(Ljava/util/function/BooleanSupplier;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", false);
    }

    static ClassNode original(Class<?> type) throws Exception {
        var node = new ClassNode();
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            new ClassReader(bytes.readAllBytes()).accept(node, ClassReader.EXPAND_FRAMES);
        }
        return node;
    }
    private static MethodNode tick(ClassNode node, String descriptor) { return node.methods.stream().filter(method -> method.name.equals("tick") && method.desc.equals(descriptor)).findFirst().orElseThrow(); }
    private static MethodNode named(ClassNode node, String name) { return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow(); }
    private static MethodInsnNode call(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return call;
        throw new AssertionError("no original " + name);
    }
    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.startsWith("net/minecraft/"))
            result.add(call.owner + "." + call.name + call.desc);
        return result;
    }
}
