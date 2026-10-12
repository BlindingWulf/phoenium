package dev.phoenium.chunk;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S9-M6 (MEM-6): locks C2meScheduledTaskReleaseMixin to the inspected C2ME task and FlowSched executor. ScheduledTask
 * keeps its action in one private final Supplier field, read once (Supplier.get) by run(Runnable) and by no other method
 * after construction, so clearing it once get() has returned changes nothing but what the finished task keeps reachable.
 * The once-only argument (dequeue CASes the task to "removed", a removed task cannot be re-prioritised, only lock waiters
 * are reset and re-enqueued) lives in the FlowSched classes, so those are pinned by class hash too. The hashes are the
 * same in the shipped settlement2 build (c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar, 8AE4F2BB...13D53), which
 * differs from this artifact only in FlowSched's ItemHolder, ItemTicket and StatusAdvancingScheduler.
 */
final class C2meScheduledTaskReleaseBinaryTest {
    private static final Path C2ME = Path.of("build", "stage6-audit-20260909", "research", "artifacts", "c2me",
            "c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar");
    private static final String C2ME_SHA256 = "B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B";
    private static final String BASE = "META-INF/jars/c2me-fabric-base-mc26.2-0.4.2-alpha.0.43-all.jar";
    private static final String TASK = "com/ishland/c2me/base/common/scheduler/ScheduledTask.class";
    private static final Map<String, String> PINNED = Map.of(
            TASK, "18243706CBB96BA4C175080420921C92C9968462C2227914061A9DF4FDEE5892",
            "com/ishland/c2me/base/common/scheduler/AbstractPosAwarePrioritizedTask.class",
            "790BEF667DFD3117111503A70991CA3BB554C924C35992ACB28A9F70FD426826",
            "com/ishland/flowsched/executor/Task.class", "59601170CBEF846C7BF812654FC1F66A88EEEA9F2BCAF1AA6678E92BF3312659",
            "com/ishland/flowsched/executor/WorkerThread.class",
            "69F8190AABFE247F52B617C4CA6956BE2B5756A613B6E2A156D0063AB3F96EF1",
            "com/ishland/flowsched/executor/ExecutorManager.class",
            "E441EE57BA5552739D8A94B77ED8E9D2012C1148CE9E53B32B389FFCCB587565",
            "com/ishland/flowsched/executor/BucketTaskPriorityQueue.class",
            "E3D9FC7AF80572BB3DFC847232AE9FB8FF7C9509EDE61C6A159B31FAF2389DB5");

    @Test void schedulerClassesAreThePinnedOnes() throws Exception {
        assertTrue(Files.isRegularFile(C2ME), "locked C2ME provider artifact is missing");
        assertEquals(C2ME_SHA256, sha256(Files.readAllBytes(C2ME)));
        Map<String, byte[]> classes = baseClasses();
        for (var pinned : PINNED.entrySet()) {
            assertNotNull(classes.get(pinned.getKey()), pinned.getKey());
            assertEquals(pinned.getValue(), sha256(classes.get(pinned.getKey())), pinned.getKey() + " changed: re-review S9-M6");
        }
    }

    @Test void actionIsOnePrivateFinalSupplierReadOnlyByRun() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(baseClasses().get(TASK)).accept(node, ClassReader.SKIP_FRAMES);
        var action = node.fields.stream().filter(f -> f.name.equals("action")).findFirst().orElseThrow();
        assertEquals("Ljava/util/function/Supplier;", action.desc);
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, action.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL));
        MethodNode run = node.methods.stream().filter(m -> m.name.equals("run") && m.desc.equals("(Ljava/lang/Runnable;)V"))
                .findFirst().orElseThrow();
        assertEquals(1, supplierGets(run), "run(Runnable) must call action.get() exactly once");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") || method == run) continue;
            assertEquals(0, actionReads(method), method.name + method.desc + " reads the action after construction");
        }
        assertEquals(1, actionReads(run));
    }

    @Test void mixinIsInstalledAndRetainedInEngineMode() throws Exception {
        String configuration;
        try (var input = java.util.Objects.requireNonNull(getClass().getResourceAsStream("/phoenium.mixins.json"))) {
            configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(configuration.contains("\"C2meScheduledTaskReleaseMixin\""), "task release adapter is not installed");
        var retained = Class.forName("dev.phoenium.mixin.PhoeniumMixinConfigPlugin").getDeclaredField("ENGINE_RETAINED");
        retained.setAccessible(true);
        assertTrue(((Set<?>) retained.get(null)).contains("dev.phoenium.mixin.C2meScheduledTaskReleaseMixin"),
                "engine mode must keep the task release adapter");
        assertTrue(C2meTaskRelease.ENABLED, "release is on by default");
        assertTrue(C2meTaskRelease.counters().contains("c2meTaskReleaseReentered=0"), C2meTaskRelease.counters());
    }

    private static Map<String, byte[]> baseClasses() throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile outer = new JarFile(C2ME.toFile())) {
            ZipEntry nested = outer.getEntry(BASE);
            assertNotNull(nested, "nested C2ME base jar");
            byte[] base = outer.getInputStream(nested).readAllBytes();
            try (JarInputStream inner = new JarInputStream(new ByteArrayInputStream(base))) {
                for (var entry = inner.getNextJarEntry(); entry != null; entry = inner.getNextJarEntry())
                    if (PINNED.containsKey(entry.getName())) classes.put(entry.getName(), inner.readAllBytes());
            }
        }
        return classes;
    }

    private static int supplierGets(MethodNode method) {
        int n = 0;
        for (AbstractInsnNode insn : method.instructions)
            if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/function/Supplier") && call.name.equals("get")
                    && call.desc.equals("()Ljava/lang/Object;")) n++;
        return n;
    }

    private static int actionReads(MethodNode method) {
        int n = 0;
        for (AbstractInsnNode insn : method.instructions)
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.name.equals("action")) n++;
        return n;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
