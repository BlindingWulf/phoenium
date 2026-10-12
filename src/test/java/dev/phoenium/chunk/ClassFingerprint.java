package dev.phoenium.chunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Deterministic fingerprint of a class's code and API, independent of debug metadata and class-file layout.
 *
 * <p>The class is read without debug information and frames (line numbers, local variable names, source file, stack
 * map frames) and rendered as canonical symbolic text: version, access flags, names, super type, interfaces, generic
 * signatures, visible annotations, inner/nest/outer-class attributes; every field (access, name, descriptor, signature,
 * constant value); every method (access, name, descriptor, signature, exceptions) with every instruction and its
 * symbolic operands (owners, names, descriptors, constants, bootstrap handles and arguments), branch, switch and
 * exception-handler targets as label ordinals, and try/catch blocks. Constant-pool indices and order, attribute order
 * and line numbers do not appear, so two byte-different encodings of the same code (e.g. Fabric Loom's line-number
 * remapping) give the same fingerprint, while any change to an instruction, member, signature or constant changes it.
 */
final class ClassFingerprint {
    private ClassFingerprint() { }

    static String of(byte[] classBytes) {
        return sha256(canonical(classBytes));
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String canonical(byte[] classBytes) {
        ClassNode c = new ClassNode();
        new ClassReader(classBytes).accept(c, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        StringBuilder out = new StringBuilder();
        out.append("class ").append(c.version).append(' ').append(c.access).append(' ').append(c.name).append(' ')
           .append(c.signature).append(' ').append(c.superName).append(' ').append(c.interfaces).append('\n');
        out.append("outer ").append(c.outerClass).append(' ').append(c.outerMethod).append(' ').append(c.outerMethodDesc)
           .append(" nestHost ").append(c.nestHostClass).append(" nestMembers ").append(c.nestMembers)
           .append(" permitted ").append(c.permittedSubclasses).append('\n');
        if (c.innerClasses != null)
            for (InnerClassNode i : c.innerClasses)
                out.append("inner ").append(i.name).append(' ').append(i.outerName).append(' ').append(i.innerName)
                   .append(' ').append(i.access).append('\n');
        out.append("annotations ").append(annotations(c.visibleAnnotations)).append('\n');
        for (FieldNode f : c.fields)
            out.append("field ").append(f.access).append(' ').append(f.name).append(' ').append(f.desc).append(' ')
               .append(f.signature).append(' ').append(constant(f.value)).append(' ')
               .append(annotations(f.visibleAnnotations)).append('\n');
        for (MethodNode m : c.methods) {
            out.append("method ").append(m.access).append(' ').append(m.name).append(m.desc).append(' ')
               .append(m.signature).append(' ').append(m.exceptions).append(' ')
               .append(annotations(m.visibleAnnotations)).append('\n');
            Map<LabelNode, Integer> labels = new HashMap<>();
            for (AbstractInsnNode insn : m.instructions)
                if (insn instanceof LabelNode label) labels.put(label, labels.size());
            for (AbstractInsnNode insn : m.instructions) out.append("  ").append(insn(insn, labels)).append('\n');
            for (TryCatchBlockNode t : m.tryCatchBlocks)
                out.append("  try L").append(labels.get(t.start)).append(" L").append(labels.get(t.end)).append(" L")
                   .append(labels.get(t.handler)).append(' ').append(t.type).append('\n');
        }
        return out.toString();
    }

    private static String insn(AbstractInsnNode insn, Map<LabelNode, Integer> labels) {
        int op = insn.getOpcode();
        return switch (insn) {
            case LabelNode label -> "L" + labels.get(label) + ":";
            case InsnNode i -> "op" + op;
            case IntInsnNode i -> "op" + op + " " + i.operand;
            case VarInsnNode v -> "op" + op + " var" + v.var;
            case TypeInsnNode t -> "op" + op + " " + t.desc;
            case FieldInsnNode f -> "op" + op + " " + f.owner + "." + f.name + ":" + f.desc;
            case MethodInsnNode mi -> "op" + op + " " + mi.owner + "." + mi.name + mi.desc + (mi.itf ? " itf" : "");
            case InvokeDynamicInsnNode d -> "indy " + d.name + d.desc + " " + handle(d.bsm) + " " + constants(d.bsmArgs);
            case JumpInsnNode j -> "op" + op + " L" + labels.get(j.label);
            case LdcInsnNode l -> "ldc " + constant(l.cst);
            case IincInsnNode i -> "iinc var" + i.var + " " + i.incr;
            case TableSwitchInsnNode t -> "tableswitch " + t.min + ".." + t.max + " L" + labels.get(t.dflt) + " "
                    + t.labels.stream().map(l -> "L" + labels.get(l)).collect(Collectors.joining(","));
            case LookupSwitchInsnNode s -> "lookupswitch L" + labels.get(s.dflt) + " " + s.keys + " "
                    + s.labels.stream().map(l -> "L" + labels.get(l)).collect(Collectors.joining(","));
            case MultiANewArrayInsnNode a -> "multianewarray " + a.desc + " " + a.dims;
            default -> insn.getClass().getSimpleName() + " op" + op;   // LineNumberNode/FrameNode cannot occur (skipped)
        };
    }

    private static String handle(Handle h) {
        return "H" + h.getTag() + " " + h.getOwner() + "." + h.getName() + h.getDesc() + (h.isInterface() ? " itf" : "");
    }

    private static String constants(Object[] values) {
        List<String> out = new ArrayList<>();
        for (Object value : values) out.add(constant(value));
        return out.toString();
    }

    private static String constant(Object value) {
        return switch (value) {
            case null -> "null";
            case Type t -> "Type:" + t.getDescriptor();
            case Handle h -> "Handle:" + handle(h);
            case ConstantDynamic d -> "Condy:" + d.getName() + d.getDescriptor() + " " + handle(d.getBootstrapMethod()) + " "
                    + constants(java.util.stream.IntStream.range(0, d.getBootstrapMethodArgumentCount())
                            .mapToObj(d::getBootstrapMethodArgument).toArray());
            case String s -> "String:" + s.replace("\\", "\\\\").replace("\n", "\\n");
            default -> value.getClass().getSimpleName() + ":" + value;
        };
    }

    private static String annotations(List<AnnotationNode> annotations) {
        if (annotations == null) return "[]";
        return annotations.stream().map(a -> a.desc + (a.values == null ? "" : a.values.toString()))
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** {@code java --class-path <asm, asm-tree jars> ClassFingerprint.java <jar> <class entry>...}: raw SHA-256 and fingerprint. */
    public static void main(String[] args) throws Exception {
        try (var jar = new java.util.zip.ZipFile(args[0])) {
            for (int i = 1; i < args.length; i++) {
                byte[] bytes = jar.getInputStream(jar.getEntry(args[i])).readAllBytes();
                System.out.println(args[i] + " raw " + sha256(bytes) + " fingerprint " + of(bytes) + " lines "
                        + canonical(bytes).lines().count());
            }
        }
    }
}
