package dev.phoenium.live;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SF-1: vanilla runs /schedule'd functions from ServerLevel.tickTime, which Phoenium executes inside the
 * live prefix owner turn. A scheduled function is a global command source and may name any dimension, so
 * it must run owner-free (Folia's global region), at the same point of the tick.
 */
class ScheduledFunctionBoundaryTest {
    private static final String TIMER_TICK = "Lnet/minecraft/world/level/timers/TimerQueue;tick(Ljava/lang/Object;J)V";

    @Test void capturesOnlyInsideTheArmedOwnerPrefixAndReplaysOnceOwnerFree() {
        var boundary = new ScheduledFunctionBoundary();
        int[] calls = {0};
        assertFalse(boundary.capture(true, () -> calls[0]++), "an unarmed boundary never defers");
        boundary.arm();
        assertFalse(boundary.capture(false, () -> calls[0]++), "an owner-free tickTime runs natively");
        assertTrue(boundary.capture(true, () -> calls[0]++));
        assertThrows(IllegalStateException.class, () -> boundary.capture(true, () -> calls[0]++),
            "one live prefix has exactly one timer-queue tick");
        assertThrows(IllegalStateException.class, () -> boundary.runOwnerFree(false), "no replay inside the prefix");
        boundary.disarm();
        assertEquals(0, calls[0], "capture does not run the timer queue");
        assertThrows(IllegalStateException.class, () -> boundary.runOwnerFree(true), "replay requires owner-free authority");
        boundary.runOwnerFree(false);
        assertEquals(1, calls[0]);
        boundary.runOwnerFree(false);
        assertEquals(1, calls[0], "a capture is replayed exactly once");
        assertEquals(1, boundary.captures());
        assertEquals(1, boundary.replays());
    }

    @Test void aCaptureThatNeverReachesTheCoordinatorIsDiscardedUncalled() {
        var boundary = new ScheduledFunctionBoundary();
        int[] calls = {0};
        boundary.arm();
        assertTrue(boundary.capture(true, () -> calls[0]++));
        boundary.discard();
        boundary.runOwnerFree(false);
        assertEquals(0, calls[0], "TimerQueue.tick was never called, so its due events stay queued");
        assertEquals(1, boundary.discards());
        boundary.arm();
        boundary.disarm();
    }

    @Test void nativeTickTimeAdvancesTheTimerQueueExactlyOnce() throws Exception {
        long ticks = 0;
        for (var i : method("net/minecraft/server/level/ServerLevel", "tickTime").instructions)
            if (i instanceof MethodInsnNode c && ("L" + c.owner + ";" + c.name + c.desc).equals(TIMER_TICK)) ticks++;
        assertEquals(1, ticks);
    }

    @Test void productionMixinWrapsThatCallAndIsRegistered() throws Exception {
        AnnotationNode wrap = annotation("dev/phoenium/mixin/ScheduledFunctionBoundaryMixin",
            "phoenium$deferScheduledFunctions", "/WrapOperation;");
        assertEquals(List.of("tickTime"), value(wrap, "method"));
        assertTrue(at(wrap).values.contains(TIMER_TICK));
        assertTrue(mixinConfig().contains("\"ScheduledFunctionBoundaryMixin\""));
    }

    @Test void runtimeArmsOnlyThePrefixAndReplaysBeforeScheduledTicks() throws Exception {
        String runtime = "dev/phoenium/live/PhoeniumLiveRuntime";
        List<String> execute = calls(node(runtime).methods.stream()
            .filter(m -> m.name.equals("execute") && m.desc.contains("LiveLevelTick")).findFirst().orElseThrow());
        int arm = execute.indexOf("arm"), prefix = execute.indexOf("phoenium$beforeScheduledTicks"),
            disarm = execute.lastIndexOf("disarm");
        assertTrue(arm >= 0 && prefix > arm && disarm > prefix, "arm -> prefix -> disarm: " + execute);
        List<String> coordinator = calls(method(runtime, "advanceOwnerFreeTickPhase"));
        int replay = coordinator.indexOf("runOwnerFree"), scheduled = coordinator.indexOf("phoenium$runScheduledTicks");
        assertTrue(replay >= 0 && scheduled > replay, "replay precedes scheduled ticks: " + coordinator);
        assertTrue(calls(method(runtime, "dispatchLevelTick")).contains("discard"), "a failed tick discards its capture");
    }

    static List<String> calls(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode c) calls.add(c.name);
        return calls;
    }

    static AnnotationNode annotation(String mixin, String handler, String descSuffix) throws Exception {
        MethodNode m = method(mixin, handler);
        var all = new ArrayList<AnnotationNode>();
        if (m.visibleAnnotations != null) all.addAll(m.visibleAnnotations);
        if (m.invisibleAnnotations != null) all.addAll(m.invisibleAnnotations);
        return all.stream().filter(a -> a.desc.endsWith(descSuffix)).findFirst().orElseThrow(() -> new AssertionError(handler));
    }

    static Object value(AnnotationNode annotation, String key) {
        int index = annotation.values.indexOf(key);
        assertTrue(index >= 0, key);
        return annotation.values.get(index + 1);
    }

    static AnnotationNode at(AnnotationNode annotation) {
        Object at = value(annotation, "at");
        return at instanceof AnnotationNode single ? single : (AnnotationNode) ((List<?>) at).getFirst();
    }

    static String mixinConfig() throws Exception {
        try (InputStream stream = ScheduledFunctionBoundaryTest.class.getResourceAsStream("/phoenium.mixins.json")) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static MethodNode method(String owner, String name) throws Exception {
        var methods = node(owner).methods.stream().filter(m -> m.name.equals(name)).toList();
        assertEquals(1, methods.size(), owner + "." + name);
        return methods.getFirst();
    }

    static ClassNode node(String owner) throws Exception {
        try (InputStream stream = ScheduledFunctionBoundaryTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(stream, owner);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
