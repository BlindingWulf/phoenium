package dev.phoenium.chunk;

import dev.phoenium.live.LiveTickPhaseBoundary;
import java.util.*;
import net.minecraft.server.level.ServerChunkCache;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executable producer and installed readiness slice, with observable collaborator effects. */
public final class C2meTickPhaseBoundaryTest {
    private static final String CACHE = LiveTickPhaseBoundary.CACHE;
    private static final String MAP = "net/minecraft/server/level/ChunkMap";
    private static final String ACCESS = "com/ishland/c2me/base/mixin/access/IThreadedAnvilChunkStorage";
    private static final String CALLBACK = "handler$test$c2me-rewrites-chunk-system$updateHolderMapAfterTick";
    private static final String DESC = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";

    @Test void supportedCallbackPreservesProducerBranchesBothResultsAndExactlyOnceEffects() throws Exception {
        for (boolean distance : List.of(false, true)) for (boolean map : List.of(false, true))
            for (boolean publication : List.of(false, true)) {
                ClassNode cache = variant();
                MethodNode original = named(cache, "tick");
                LiveTickPhaseBoundary.installCache(cache); // Red on the old immediate-POP integration.
                Class<?> executable = executable(cache, original, named(cache, "phoenium$prepareChunkTick"));
                List<String> expected = new ArrayList<>(List.of("distance", "promote", "generation"));
                if (distance || map) expected.add("clear");
                expected.add("publish");
                for (String method : List.of("originalSlice", "preparedSlice")) {
                    HarnessCache target = (HarnessCache) executable.getConstructor().newInstance();
                    target.distanceManager.result = distance; target.chunkMap.result = map;
                    target.chunkMap.publication = publication;
                    executable.getMethod(method).invoke(target);
                    assertEquals(expected, target.events, method + " must keep both result consumers and all effects once");
                    target.events.clear();
                    // Call the original Minecraft producer itself: only distance OR map changes return true.
                    assertEquals(distance || map, executable.getMethod("runDistanceManagerUpdates").invoke(target));
                    assertEquals(expected.subList(0, expected.size() - 1), target.events);
                }
            }
    }

    @Test void rejectsUnknownCallbackChangedPublicationAndChangedResultConsumer() throws Exception {
        ClassNode unknown = variant();
        call(named(unknown, "tick"), CALLBACK).name = "handler$test$unknown$publish";
        rejected(unknown, "unreviewed distance-update callback");
        ClassNode extraEffect = variant();
        named(extraEffect, CALLBACK).instructions.insert(new InsnNode(Opcodes.NOP));
        rejected(extraEffect, "holder-map callback effects/result consumption changed");
        ClassNode addedLock = variant();
        named(addedLock, CALLBACK).access |= Opcodes.ACC_SYNCHRONIZED;
        rejected(addedLock, "holder-map callback effects/result consumption changed");
        ClassNode wrongResult = variant();
        MethodNode callback = named(wrongResult, CALLBACK);
        callback.instructions.set(call(callback, "invokeUpdateHolderMap"),
            new MethodInsnNode(Opcodes.INVOKEINTERFACE, ACCESS, "differentPublication", "()Z", true));
        rejected(wrongResult, "holder-map callback effects/result consumption changed");
        ClassNode consumer = variant();
        MethodInsnNode site = call(named(consumer, "tick"), CALLBACK);
        named(consumer, "tick").instructions.set(site.getNext(), new InsnNode(Opcodes.DUP));
        rejected(consumer, "distance-update result consumption changed after holder-map callback");
    }

    @Test void publicationFailureEscapesOnceAfterTheOriginalProducerEffects() throws Exception {
        ClassNode cache = variant(); MethodNode original = named(cache, "tick");
        LiveTickPhaseBoundary.installCache(cache);
        Class<?> executable = executable(cache, original, named(cache, "phoenium$prepareChunkTick"));
        for (String method : List.of("originalSlice", "preparedSlice")) {
            HarnessCache target = (HarnessCache) executable.getConstructor().newInstance();
            RuntimeException failure = new IllegalStateException("publication failed");
            target.chunkMap.failure = failure;
            var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> executable.getMethod(method).invoke(target));
            assertSame(failure, thrown.getCause());
            assertEquals(List.of("distance", "promote", "generation", "publish"), target.events);
        }
    }

    private static void rejected(ClassNode cache, String message) {
        assertTrue(assertThrows(IllegalStateException.class, () -> LiveTickPhaseBoundary.installCache(cache)).getMessage().contains(message));
        assertTrue(cache.methods.stream().noneMatch(m -> m.name.equals("phoenium$prepareChunkTick")));
    }

    // Independently authored fixture matching the exact locked binary's six-opcode callback.
    private static ClassNode variant() throws Exception {
        ClassNode cache = LiveTickPhaseBoundaryTest.original(ServerChunkCache.class);
        MethodNode callback = new MethodNode(Opcodes.ACC_PRIVATE, CALLBACK, DESC, null, null);
        callback.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        callback.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, CACHE, "chunkMap", "L" + MAP + ";"));
        callback.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ACCESS));
        callback.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ACCESS, "invokeUpdateHolderMap", "()Z", true));
        callback.instructions.add(new InsnNode(Opcodes.POP)); callback.instructions.add(new InsnNode(Opcodes.RETURN));
        callback.maxLocals = 2; callback.maxStack = 1; cache.methods.add(callback);
        InsnList invocation = new InsnList();
        invocation.add(new VarInsnNode(Opcodes.ALOAD, 0)); invocation.add(new InsnNode(Opcodes.ACONST_NULL));
        invocation.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CACHE, CALLBACK, DESC, false));
        named(cache, "tick").instructions.insert(call(named(cache, "tick"), "runDistanceManagerUpdates"), invocation);
        return cache;
    }

    private static Class<?> executable(ClassNode cache, MethodNode original, MethodNode prepared) {
        ClassNode test = new ClassNode(); test.version = Opcodes.V25; test.access = Opcodes.ACC_PUBLIC;
        test.name = CACHE; test.superName = Type.getInternalName(HarnessCache.class);
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, test.superName, "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN)); test.methods.add(init);
        MethodNode producer = named(cache, "runDistanceManagerUpdates"); producer.access = Opcodes.ACC_PUBLIC;
        test.methods.add(producer); test.methods.add(named(cache, CALLBACK));
        test.methods.add(slice(original, "originalSlice")); test.methods.add(slice(prepared, "preparedSlice"));
        String generated = "dev/phoenium/chunk/C2meExecutableCache";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        test.accept(new ClassRemapper(writer, new SimpleRemapper(Map.of(CACHE, generated,
            MAP, Type.getInternalName(HarnessMap.class), ACCESS, Type.getInternalName(Publication.class),
            "net/minecraft/server/level/DistanceManager", Type.getInternalName(HarnessDistance.class)))));
        return new ClassLoader(C2meTickPhaseBoundaryTest.class.getClassLoader()) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(null, bytes, 0, bytes.length); }
        }.define();
    }

    private static MethodNode slice(MethodNode method, String name) {
        MethodNode slice = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
        AbstractInsnNode start = call(method, "runDistanceManagerUpdates").getPrevious();
        while (start.getOpcode() < 0) start = start.getPrevious();
        AbstractInsnNode end = call(method, CALLBACK).getNext();
        while (end.getOpcode() < 0) end = end.getNext();
        assertEquals(Opcodes.POP, end.getOpcode());
        for (AbstractInsnNode instruction = start; instruction != end.getNext(); instruction = instruction.getNext())
            if (instruction.getOpcode() >= 0) slice.instructions.add(instruction.clone(new HashMap<>()));
        slice.instructions.add(new InsnNode(Opcodes.RETURN)); return slice;
    }
    private static MethodNode named(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
    private static MethodInsnNode call(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return call;
        throw new AssertionError("missing " + name);
    }

    public interface Publication { boolean invokeUpdateHolderMap(); }
    public static class HarnessCache {
        public final List<String> events = new ArrayList<>();
        public final HarnessMap chunkMap = new HarnessMap(events);
        public final HarnessDistance distanceManager = new HarnessDistance(events);
        public void clearCache() { events.add("clear"); }
    }
    public static class HarnessMap implements Publication {
        final List<String> events; boolean result, publication;
        RuntimeException failure;
        HarnessMap(List<String> events) { this.events = events; }
        public boolean promoteChunkMap() { events.add("promote"); return result; }
        public void runGenerationTasks() { events.add("generation"); }
        public boolean invokeUpdateHolderMap() { events.add("publish"); if (failure != null) throw failure; return publication; }
    }
    public static class HarnessDistance {
        final List<String> events; boolean result;
        HarnessDistance(List<String> events) { this.events = events; }
        public boolean runAllUpdates(HarnessMap map) { events.add("distance"); return result; }
    }
}
