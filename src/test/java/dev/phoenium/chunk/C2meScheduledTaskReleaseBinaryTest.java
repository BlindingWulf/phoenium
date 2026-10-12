package dev.phoenium.chunk;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * S9-M6 (MEM-6): locks C2meScheduledTaskReleaseMixin to the inspected C2ME task. ScheduledTask keeps its action in one
 * private final Supplier field, read once (Supplier.get) by run(Runnable) and by no other method after construction, so
 * clearing it once get() has returned changes nothing but what the finished task keeps reachable. The mixin is
 * installed and retained in engine mode.
 */
final class C2meScheduledTaskReleaseBinaryTest {
    private static final Path C2ME = Path.of("build", "stage6-audit-20260909", "research", "artifacts", "c2me",
            "c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar");
    private static final String BASE = "META-INF/jars/c2me-fabric-base-mc26.2-0.4.2-alpha.0.43-all.jar";
    private static final String TASK = "com/ishland/c2me/base/common/scheduler/ScheduledTask.class";

    @Test void actionIsOnePrivateFinalSupplierReadOnlyByRun() throws Exception {
        ClassNode node = taskClass();
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
    }

    private static ClassNode taskClass() throws Exception {
        try (JarFile outer = new JarFile(C2ME.toFile())) {
            ZipEntry nested = outer.getEntry(BASE);
            assertNotNull(nested, "nested C2ME base jar");
            byte[] base = outer.getInputStream(nested).readAllBytes();
            try (JarInputStream inner = new JarInputStream(new ByteArrayInputStream(base))) {
                for (var entry = inner.getNextJarEntry(); entry != null; entry = inner.getNextJarEntry()) {
                    if (!entry.getName().equals(TASK)) continue;
                    ClassNode node = new ClassNode();
                    new ClassReader(inner.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
                    return node;
                }
            }
        }
        throw new AssertionError("ScheduledTask not found in " + C2ME);
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
}
