package dev.phoenium.chunk;

import java.util.function.Consumer;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ChunkStatusTasks pin of FullConstructionFragments (S9 FULLNONSELF-48): it must accept the audited code in any
 * encoding (with or without Loom's line map) and reject any change to its code or API. A mismatch fails here, once and
 * by name, instead of as 48 opaque prototype failures.
 */
final class FullConstructionFragmentsPinTest {
    private static byte[] loaded() throws Exception {
        try (var input = ChunkStatusTasks.class.getResourceAsStream("ChunkStatusTasks.class")) {
            return input.readAllBytes();
        }
    }

    private static byte[] rewrite(byte[] bytes, int readFlags, Consumer<ClassNode> change) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, readFlags);
        change.accept(node);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode firstCall(ClassNode node) {
        for (MethodNode method : node.methods)
            for (AbstractInsnNode insn : method.instructions)
                if (insn instanceof MethodInsnNode call) return call;
        throw new AssertionError("no call in " + node.name);
    }

    @Test void loadedMinecraftClassIsTheAuditedCode() throws Exception {
        byte[] bytes = loaded();
        assertEquals(FullConstructionFragments.AUDITED_FINGERPRINT, ClassFingerprint.of(bytes),
                "loaded ChunkStatusTasks (raw SHA-256 " + ClassFingerprint.sha256(bytes) + ") is not the audited code");
    }

    @Test void debugDataAndClassFileLayoutDoNotChangeTheFingerprint() throws Exception {
        byte[] bytes = loaded();
        String expected = ClassFingerprint.of(bytes);
        byte[] stripped = rewrite(bytes, ClassReader.SKIP_DEBUG, node -> { });            // no debug attributes, new pool
        byte[] shifted = rewrite(bytes, 0, node -> {                                        // what a Loom line map does
            for (MethodNode method : node.methods)
                for (AbstractInsnNode insn : method.instructions)
                    if (insn instanceof LineNumberNode line) line.line += 7;
        });
        assertNotEquals(ClassFingerprint.sha256(bytes), ClassFingerprint.sha256(stripped));
        assertNotEquals(ClassFingerprint.sha256(bytes), ClassFingerprint.sha256(shifted));
        assertEquals(expected, ClassFingerprint.of(stripped));
        assertEquals(expected, ClassFingerprint.of(shifted));
    }

    @Test void codeAndApiChangesChangeTheFingerprint() throws Exception {
        byte[] bytes = loaded();
        String original = ClassFingerprint.of(bytes);
        assertEquals(original, ClassFingerprint.of(rewrite(bytes, 0, node -> { })), "an unchanged rewrite is the same code");
        assertNotEquals(original, ClassFingerprint.of(rewrite(bytes, 0, node -> firstCall(node).name += "X")), "call target");
        assertNotEquals(original, ClassFingerprint.of(rewrite(bytes, 0, node -> firstCall(node).owner += "X")), "call owner");
        assertNotEquals(original, ClassFingerprint.of(rewrite(bytes, 0,
                node -> node.methods.get(0).access ^= Opcodes.ACC_SYNCHRONIZED)), "method access");
        assertNotEquals(original, ClassFingerprint.of(rewrite(bytes, 0, node -> {
            MethodNode method = node.methods.stream().filter(m -> m.instructions.size() > 0).findFirst().orElseThrow();
            method.instructions.insert(new InsnNode(Opcodes.NOP));
        })), "an added instruction");
        assertNotEquals(original, ClassFingerprint.of(rewrite(bytes, 0,
                node -> node.methods.get(node.methods.size() - 1).name += "Renamed")), "method name");
        assertNotEquals(original, ClassFingerprint.of(rewrite(bytes, 0,
                node -> node.interfaces.add("java/io/Serializable"))), "type hierarchy");
        assertNotEquals(original, ClassFingerprint.of(rewrite(bytes, 0,
                node -> node.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_STATIC, "phoeniumProbe", "I", null, 1)))),
                "an added field");
    }
}
