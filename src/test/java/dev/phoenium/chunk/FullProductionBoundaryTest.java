package dev.phoenium.chunk;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production selector/adapter; generated test classes stay in this JUnit process. */
class FullProductionBoundaryTest {
    private static int sequence;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualProtoAndReloadIdentityFinishingAndPublicSlotStaySeparate(boolean reload) throws Throwable {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var node = FullNestedQueryBoundaryTest.primary(f);
        if (reload) node = FullNestedQueryBoundaryTest.reload(f, node);
        var operations = executable(original()); AtomicInteger unsaved = new AtomicInteger();
        var context = new WorldGenContext(f.level, null, null, null,
                command -> fail("boundary adapter cannot submit or pump"), chunk -> unsaved.incrementAndGet());
        var actual = operations.prepare(node.input(), context, node.holder());
        assertSame(actual, node.actual()); assertFalse(node.full().isDone());
        assertEquals(false, field(actual, LevelChunk.class, "loaded"));
        assertNotNull(field(actual, LevelChunk.class, "postLoad")); assertTrue(f.inserted.isEmpty());
        assertSame(actual, operations.finish(node.input(), context, node.holder(), actual));
        assertSame(actual, node.actual()); assertFalse(node.full().isDone());
        assertEquals(true, field(actual, LevelChunk.class, "loaded"));
        assertNull(field(actual, LevelChunk.class, "postLoad")); assertEquals(1, f.inserted.size());
        assertEquals(1, unsaved.get(), "this in-memory reload helper uses the proto constructor's dirty state");
    }

    @Test void returnCallbackIsMovedWholeAndNeverRunsForPreparation() throws Throwable {
        ClassNode source = original();
        MethodNode method = supplier(source);
        // A deterministic return callback stands in for the injected callback invocation here.
        // The actual installed Fabric callback is separately checked in the exported server class.
        for (AbstractInsnNode node : method.instructions.toArray()) if (node.getOpcode() == Opcodes.ARETURN) {
            InsnList callback = new InsnList();
            callback.add(new InsnNode(Opcodes.DUP));
            callback.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
            callback.add(new InsnNode(Opcodes.POP)); method.instructions.insertBefore(node, callback);
        }
        FullStageBoundary.install(source);
        MethodNode preparation = named(source, FullStageBoundary.PREPARE);
        MethodNode finishing = named(source, FullStageBoundary.FINISH);
        assertEquals(0, calls(preparation, "getClass")); assertEquals(1, calls(finishing, "getClass"));
        assertEquals(0, calls(preparation, "runPostLoad")); assertEquals(1, calls(finishing, "runPostLoad"));
        assertEquals(1, calls(preparation, "replaceProtoChunk")); assertEquals(0, calls(finishing, "replaceProtoChunk"));
        assertEquals(0, calls(finishing, "<init>"));
    }

    @Test void originalAsyncSupplierAndProtoExceptionHandlersRemainIntact() throws Exception {
        ClassNode node = original();
        byte[] before = bytes(node);
        var originalSupplier = supplier(node); List<AbstractInsnNode> instructions = List.of(originalSupplier.instructions.toArray());
        FullStageBoundary.install(node);
        assertSame(originalSupplier, supplier(node)); assertEquals(instructions, List.of(originalSupplier.instructions.toArray()));
        assertEquals(1, calls(named(node, "full"), "supplyAsync"));
        assertEquals(2, named(node, "lambda$full$1").tryCatchBlocks.size());
        assertTrue(before.length < bytes(node).length);
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.install(node));
    }

    @Test void changedSideEffectOrderFailsBeforeAnyAdapterIsInstalled() throws Exception {
        ClassNode node = original(); MethodNode method = supplier(node);
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.name.equals("setLoaded")) { call.name = "setChanged"; break; }
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.install(node));
        assertTrue(node.methods.stream().noneMatch(m -> m.name.equals(FullStageBoundary.PREPARE)));
    }

    @Test void exceptionHandlerAcrossBoundaryIsRejected() throws Exception {
        ClassNode node = original(); MethodNode method = supplier(node);
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        method.instructions.insert(start); method.instructions.add(end); method.instructions.add(handler);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.install(node));
    }

    @Test void jumpCrossingBoundaryIsRejected() throws Exception {
        ClassNode node = original(); MethodNode method = supplier(node); LabelNode target = new LabelNode();
        method.instructions.insertBefore(method.instructions.getLast(), target);
        method.instructions.insert(new JumpInsnNode(Opcodes.GOTO, target));
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.install(node));
    }

    @Test void wrongTargetOrMissingMethodCannotBeRewritten() throws Exception {
        ClassNode node = original(); node.name = "another/Class";
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.install(node));
        ClassNode missing = original(); missing.methods.remove(supplier(missing));
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.install(missing));
    }

    @Test void productionPluginRequiresTheInstalledFabricAndPhoeniumCallbacks() throws Exception {
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.requireInstalledCallbacks(original()));
        ClassNode node = withInstalledCallbacks();
        assertDoesNotThrow(() -> FullStageBoundary.requireInstalledCallbacks(node));
    }

    @Test void duplicateFabricCallbackIsRejected() throws Exception {
        ClassNode node = withInstalledCallbacks();
        supplier(node).instructions.insertBefore(supplier(node).instructions.getLast(), callback("fabric-lifecycle-events-v1$onChunkLoad"));
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.requireInstalledCallbacks(node));
    }

    @Test void earlyFabricCallbackOrUnknownSubscriberInjectionIsRejected() throws Exception {
        ClassNode early = withInstalledCallbacks();
        supplier(early).instructions.insert(callback("fabric-lifecycle-events-v1$onChunkLoad"));
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.requireInstalledCallbacks(early));
        ClassNode unknown = withInstalledCallbacks();
        supplier(unknown).instructions.insert(callback("unreviewed$callback"));
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.requireInstalledCallbacks(unknown));
    }

    private static ClassNode withInstalledCallbacks() throws Exception {
        ClassNode node = original(); MethodNode method = supplier(node);
        method.instructions.insert(callback("phoenium$fullConversionStarted"));
        method.instructions.insertBefore(method.instructions.getLast(), callback("fabric-lifecycle-events-v1$onChunkLoad"));
        method.instructions.insertBefore(method.instructions.getLast(), callback("phoenium$fullConversionReturned"));
        return node;
    }
    private static MethodInsnNode callback(String suffix) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, FullStageBoundary.TARGET, "handler$test$" + suffix,
                FullStageBoundary.CALLBACK_DESCRIPTOR, false);
    }

    static ClassNode original() throws Exception {
        ClassNode node = new ClassNode();
        try (var stream = ChunkStatusTasks.class.getResourceAsStream("ChunkStatusTasks.class")) {
            new ClassReader(stream.readAllBytes()).accept(node, ClassReader.EXPAND_FRAMES);
        }
        return node;
    }

    @Test void asyncRegistrationRetainsOriginalSupplierAndBindsExactlyOneGeneratedPreparationHandle() throws Exception {
        ClassNode node = original(); FullStageBoundary.install(node); FullStageBoundary.installRegistration(node);
        var wrapper = named(node, "full");
        assertEquals(1, calls(wrapper, "registerAsync")); assertEquals(0, calls(wrapper, "supplyAsync"));
        var handles = java.util.Arrays.stream(wrapper.instructions.toArray()).filter(i -> i instanceof InvokeDynamicInsnNode)
                .map(i -> (Handle) ((InvokeDynamicInsnNode) i).bsmArgs[1]).map(Handle::getName).toList();
        assertEquals(List.of(FullStageBoundary.SUPPLIER, FullStageBoundary.PREPARE), handles);
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.installRegistration(node));
    }

    @Test void asyncSelectorRejectsMissingAdapterChangedExecutorAndUnreviewedOperations() throws Exception {
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.installRegistration(original()));
        var changed = original(); FullStageBoundary.install(changed);
        for (AbstractInsnNode i : named(changed, "full").instructions) if (i instanceof MethodInsnNode call && call.name.equals("mainThreadExecutor"))
            call.name = "otherExecutor";
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.installRegistration(changed));
        var extra = original(); FullStageBoundary.install(extra);
        named(extra, "full").instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "unknown/Mod", "callback", "()V", false));
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.installRegistration(extra));
    }

    @Test void asyncSelectorRejectsChangedHolderCaptureAndSupplierLineage() throws Exception {
        var changed = original(); FullStageBoundary.install(changed);
        for (AbstractInsnNode i : named(changed, "full").instructions) if (i instanceof InvokeDynamicInsnNode indy)
            indy.bsmArgs[1] = new Handle(Opcodes.H_INVOKESTATIC, FullStageBoundary.TARGET, "unknownSupplier", FullStageBoundary.DESCRIPTOR, false);
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.installRegistration(changed));
        var local = original(); FullStageBoundary.install(local);
        for (AbstractInsnNode i : named(local, "full").instructions) if (i instanceof InvokeDynamicInsnNode) {
            ((VarInsnNode) i.getPrevious()).var = 4; break;
        }
        assertThrows(IllegalStateException.class, () -> FullStageBoundary.installRegistration(local));
    }
    static Operations executable(ClassNode node) throws Exception {
        FullStageBoundary.install(node);
        String generated = node.name + "PhoeniumProductionBoundaryTest" + (++sequence);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(node.name, generated)));
        Class<?> type = MethodHandles.privateLookupIn(ChunkStatusTasks.class, MethodHandles.lookup()).defineClass(writer.toByteArray());
        return new Operations(type.getMethod(FullStageBoundary.PREPARE, ChunkAccess.class, WorldGenContext.class, GenerationChunkHolder.class),
                type.getMethod(FullStageBoundary.FINISH, ChunkAccess.class, WorldGenContext.class, GenerationChunkHolder.class, LevelChunk.class));
    }
    record Operations(Method preparation, Method finishing) {
        LevelChunk prepare(ChunkAccess input, WorldGenContext context, GenerationChunkHolder holder) throws Throwable {
            return invoke(preparation, input, context, holder);
        }
        LevelChunk finish(ChunkAccess input, WorldGenContext context, GenerationChunkHolder holder, LevelChunk actual) throws Throwable {
            return invoke(finishing, input, context, holder, actual);
        }
        private static LevelChunk invoke(Method method, Object... args) throws Throwable {
            try { return (LevelChunk) method.invoke(null, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
    private static MethodNode supplier(ClassNode node) { return named(node, FullStageBoundary.SUPPLIER); }
    private static MethodNode named(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
    private static long calls(MethodNode method, String name) {
        return java.util.Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call && call.name.equals(name)).count();
    }
    private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
}
