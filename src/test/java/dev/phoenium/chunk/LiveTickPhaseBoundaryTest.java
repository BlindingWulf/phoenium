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
        expected.remove(LiveTickPhaseBoundary.CACHE + ".tickChunks()V");
        expected.remove("net/minecraft/world/level/entity/EntityTickList.forEach(Ljava/util/function/Consumer;)V");
        expected.remove(LiveTickPhaseBoundary.LEVEL + ".tickBlockEntities()V");
        expected.remove("net/minecraft/world/level/dimension/end/EnderDragonFight.tick()V");
        LiveTickPhaseBoundary.installLevel(level); LiveTickPhaseBoundary.installCache(cache);
        List<String> actual = new ArrayList<>();
        actual.addAll(calls(named(level, "phoenium$beforeScheduledTicks")));
        actual.addAll(calls(named(level, "phoenium$runScheduledTicks")));
        actual.addAll(calls(named(level, "phoenium$beforeChunkTick")));
        actual.addAll(calls(named(cache, "phoenium$prepareChunkTick")));
        actual.addAll(calls(named(cache, "phoenium$finishChunkTick")));
        actual.addAll(calls(named(level, "phoenium$afterChunkTick")));
        actual.addAll(calls(named(cache, "phoenium$finishChunkMaintenance")));
        actual.addAll(calls(named(level, "phoenium$runBlockEvents")));
        actual.addAll(calls(named(level, "phoenium$beforeEntityTicks")));
        actual.addAll(calls(named(level, "phoenium$beforeEntityList")));
        actual.addAll(calls(named(level, "phoenium$runEntityTicks")));
        actual.addAll(calls(named(level, "phoenium$afterEntityList")));
        actual.addAll(calls(named(level, "phoenium$runEntityManagement")));
        actual.addAll(calls(named(level, "phoenium$afterEntityTicks")));
        assertEquals(expected, actual, "Control preparation and resumed gameplay retain the original complete call order");
        assertEquals(1, allCalls(named(cache, "phoenium$finishChunkTick")).stream()
            .filter(call -> call.owner.equals("dev/phoenium/live/LiveTickPhases")
                && call.name.equals("runChunkGameplay")).count());
        assertEquals(1, allCalls(named(level, "phoenium$runEntityTicks")).stream()
            .filter(call -> call.owner.equals("dev/phoenium/live/LiveTickPhases")
                && call.name.equals("runEntityTicks")).count());
        assertEquals(1, allCalls(named(level, "phoenium$afterEntityList")).stream()
            .filter(call -> call.owner.equals("dev/phoenium/live/LiveTickPhases")
                && call.name.equals("runBlockEntities")).count());
        assertEquals(1, allCalls(named(level, "phoenium$beforeEntityList")).stream()
            .filter(call -> call.owner.equals("dev/phoenium/live/LiveTickPhases")
                && call.name.equals("runDragonFight")).count());
        assertEquals(originalLevelInstructions, List.of(levelTick.instructions.toArray()));
        assertEquals(originalCacheInstructions, List.of(cacheTick.instructions.toArray()));
        assertTrue(level.interfaces.contains("dev/phoenium/live/LiveTickPhases$LevelAccess"));
        assertTrue(cache.interfaces.contains("dev/phoenium/live/LiveTickPhases$CacheAccess"));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installLevel(level));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installCache(cache));
    }

    @Test void entityStorageManagementHasItsOwnCutAndRejectsDuplicatedOriginalWork() throws Exception {
        ClassNode level = original(ServerLevel.class);
        LiveTickPhaseBoundary.installLevel(level);
        String manager = "net/minecraft/world/level/entity/PersistentEntitySectionManager.tick()V";
        assertEquals(1, calls(named(level, "phoenium$runEntityManagement")).stream().filter(manager::equals).count());
        assertFalse(calls(named(level, "phoenium$afterEntityTicks")).contains(manager));
        ClassNode changed = original(ServerLevel.class);
        MethodNode nativeTick = tick(changed, "(Ljava/util/function/BooleanSupplier;)V");
        var original = allCalls(nativeTick).stream().filter(call -> (call.owner + "." + call.name + call.desc).equals(manager)).findFirst().orElseThrow();
        nativeTick.instructions.insertBefore(original, original.clone(null));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installLevel(changed));
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

    @Test void reviewedServerCoreActivationCallbackStaysAtTheEntityBoundary() throws Exception {
        ClassNode level = original(ServerLevel.class);
        MethodNode tick = tick(level, "(Ljava/util/function/BooleanSupplier;)V");
        tick.instructions.insert(event("startLevelTick"));
        tick.instructions.insertBefore(tick.instructions.getLast(), event("endLevelTick"));
        MethodInsnNode entityTicks = call(tick, "forEach");
        String name = "handler$test$servercore$activateEntities";
        tick.instructions.insertBefore(entityTicks, new VarInsnNode(Opcodes.ALOAD, 0));
        tick.instructions.insertBefore(entityTicks, new VarInsnNode(Opcodes.ALOAD, 1));
        tick.instructions.insertBefore(entityTicks, new InsnNode(Opcodes.ACONST_NULL));
        tick.instructions.insertBefore(entityTicks, serverCoreCallback(name));
        level.methods.add(serverCoreCallbackBody(name));
        assertDoesNotThrow(() -> LiveTickPhaseBoundary.requireInstalledLevelCallbacks(level));
        LiveTickPhaseBoundary.installLevel(level);
        assertEquals(1, allCalls(named(level, "phoenium$runEntityTicks")).stream()
            .filter(call -> call.owner.equals(LiveTickPhaseBoundary.LEVEL)
                && call.name.equals(name)).count());

        ClassNode moved = original(ServerLevel.class);
        MethodNode movedTick = tick(moved, "(Ljava/util/function/BooleanSupplier;)V");
        movedTick.instructions.insert(event("startLevelTick"));
        movedTick.instructions.insertBefore(movedTick.instructions.getLast(), event("endLevelTick"));
        MethodInsnNode movedEntityTicks = call(movedTick, "forEach");
        movedTick.instructions.insert(movedEntityTicks, serverCoreCallback(name));
        moved.methods.add(serverCoreCallbackBody(name));
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.requireInstalledLevelCallbacks(moved));

        ClassNode changed = original(ServerLevel.class);
        MethodNode changedTick = tick(changed, "(Ljava/util/function/BooleanSupplier;)V");
        changedTick.instructions.insert(event("startLevelTick"));
        changedTick.instructions.insertBefore(changedTick.instructions.getLast(), event("endLevelTick"));
        MethodInsnNode changedEntityTicks = call(changedTick, "forEach");
        changedTick.instructions.insertBefore(changedEntityTicks, new VarInsnNode(Opcodes.ALOAD, 0));
        changedTick.instructions.insertBefore(changedEntityTicks, new VarInsnNode(Opcodes.ALOAD, 1));
        changedTick.instructions.insertBefore(changedEntityTicks, new InsnNode(Opcodes.ACONST_NULL));
        changedTick.instructions.insertBefore(changedEntityTicks, serverCoreCallback(name));
        MethodNode changedBody = serverCoreCallbackBody(name);
        changedBody.instructions.set(changedBody.instructions.get(5), new IntInsnNode(Opcodes.BIPUSH, 19));
        changed.methods.add(changedBody);
        assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.requireInstalledLevelCallbacks(changed));
    }

    private static MethodInsnNode event(String name) {
        return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LiveTickPhaseBoundary.LEVEL,
                "handler$test$fabric-lifecycle-events-v1$" + name,
                "(Ljava/util/function/BooleanSupplier;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", false);
    }

    private static MethodInsnNode serverCoreCallback(String name) {
        return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LiveTickPhaseBoundary.LEVEL, name,
            "(Ljava/util/function/BooleanSupplier;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", false);
    }

    private static MethodNode serverCoreCallbackBody(String name) {
        String descriptor = "(Ljava/util/function/BooleanSupplier;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
        MethodNode body = new MethodNode(Opcodes.ACC_PRIVATE, name, descriptor, null, null);
        LabelNode skip = new LabelNode();
        body.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, LiveTickPhaseBoundary.LEVEL, "server",
            "Lnet/minecraft/server/MinecraftServer;"));
        body.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/MinecraftServer",
            "getTickCount", "()I", false));
        body.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        body.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        body.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        body.instructions.add(new InsnNode(Opcodes.IREM));
        body.instructions.add(new JumpInsnNode(Opcodes.IFNE, skip));
        body.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        body.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "me/wesley1808/servercore/common/activation_range/ActivationRange", "activateEntities",
            "(L" + LiveTickPhaseBoundary.LEVEL + ";I)V", false));
        body.instructions.add(skip);
        body.instructions.add(new InsnNode(Opcodes.RETURN));
        body.maxLocals = 4; body.maxStack = 2;
        return body;
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
    private static List<MethodInsnNode> allCalls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }
}
