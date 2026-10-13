package dev.phoenium.chunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Deterministic fingerprint of a class's code and API, independent of debug metadata and class-file layout.
 *
 * <p>The class is read without debug information and frames (line numbers, local variable names and parameter names,
 * source file, stack map frames, all derived or descriptive) and rendered as canonical symbolic text:
 * <ul>
 * <li>the class: version, access flags (including the deprecated and synthetic markers), name, generic signature, super
 *     type, interfaces, outer class and enclosing method, nest host and members, permitted subclasses, inner classes,
 *     record components, module name;</li>
 * <li>every field: access, name, descriptor, generic signature, constant value;</li>
 * <li>every method: access, name, descriptor, generic signature, declared exceptions, annotation default, then every
 *     instruction with its symbolic operands (owners, names, descriptors, constants, invokedynamic and constant-dynamic
 *     bootstrap handles and arguments), branch and switch targets as label ordinals, and the exception table in order
 *     (range, handler, caught type);</li>
 * <li>every annotation, visible and invisible, wherever it occurs: on the class, fields, methods, parameters (with the
 *     annotable parameter counts), record components, and type annotations (target, path) on any of those, on
 *     instructions, exception handlers and local variables (ranges and slots); annotation values recursively (enum
 *     constants, nested annotations, arrays, class literals);</li>
 * <li>the names of any non-standard attributes.</li>
 * </ul>
 * Labels get ordinals only if something refers to them, in instruction order. Strings are length-prefixed and floating
 * point constants are rendered by their raw bits, so the text is unambiguous. Constant-pool indices and order, attribute
 * order and line numbers do not appear, so two byte-different encodings of the same code (e.g. Fabric Loom's line-number
 * remapping) give the same fingerprint, while any change to an instruction, member, signature, constant, exception
 * handler, bootstrap reference or annotation changes it (ClassFingerprintTest mutates each).
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
        if (c.module != null) out.append("module ").append(c.module.name).append(' ').append(c.module.access).append('\n');
        if (c.innerClasses != null)
            for (InnerClassNode i : c.innerClasses)
                out.append("inner ").append(i.name).append(' ').append(i.outerName).append(' ').append(i.innerName)
                   .append(' ').append(i.access).append('\n');
        out.append("annotations").append(annotations(c.visibleAnnotations, c.invisibleAnnotations))
           .append(typeAnnotations(c.visibleTypeAnnotations, c.invisibleTypeAnnotations)).append(attributes(c.attrs)).append('\n');
        if (c.recordComponents != null)
            for (RecordComponentNode r : c.recordComponents)
                out.append("record ").append(r.name).append(' ').append(r.descriptor).append(' ').append(r.signature)
                   .append(annotations(r.visibleAnnotations, r.invisibleAnnotations))
                   .append(typeAnnotations(r.visibleTypeAnnotations, r.invisibleTypeAnnotations)).append(attributes(r.attrs))
                   .append('\n');
        for (FieldNode f : c.fields)
            out.append("field ").append(f.access).append(' ').append(f.name).append(' ').append(f.desc).append(' ')
               .append(f.signature).append(' ').append(constant(f.value))
               .append(annotations(f.visibleAnnotations, f.invisibleAnnotations))
               .append(typeAnnotations(f.visibleTypeAnnotations, f.invisibleTypeAnnotations)).append(attributes(f.attrs))
               .append('\n');
        for (MethodNode m : c.methods) method(m, out);
        return out.toString();
    }

    private static void method(MethodNode m, StringBuilder out) {
        out.append("method ").append(m.access).append(' ').append(m.name).append(m.desc).append(' ')
           .append(m.signature).append(' ').append(m.exceptions)
           .append(annotations(m.visibleAnnotations, m.invisibleAnnotations))
           .append(typeAnnotations(m.visibleTypeAnnotations, m.invisibleTypeAnnotations)).append(attributes(m.attrs));
        if (m.annotationDefault != null) out.append(" default ").append(value(m.annotationDefault));
        out.append('\n');
        parameters(out, "visibleParameters", m.visibleAnnotableParameterCount, m.visibleParameterAnnotations);
        parameters(out, "invisibleParameters", m.invisibleAnnotableParameterCount, m.invisibleParameterAnnotations);

        List<LabelNode> referenced = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions)
            switch (insn) {
                case JumpInsnNode j -> referenced.add(j.label);
                case TableSwitchInsnNode t -> { referenced.add(t.dflt); referenced.addAll(t.labels); }
                case LookupSwitchInsnNode s -> { referenced.add(s.dflt); referenced.addAll(s.labels); }
                default -> { }
            }
        for (TryCatchBlockNode t : m.tryCatchBlocks) referenced.addAll(List.of(t.start, t.end, t.handler));
        for (List<LocalVariableAnnotationNode> list : java.util.Arrays.asList(m.visibleLocalVariableAnnotations,
                m.invisibleLocalVariableAnnotations))
            if (list != null)
                for (LocalVariableAnnotationNode a : list) { referenced.addAll(a.start); referenced.addAll(a.end); }
        Map<LabelNode, Integer> labels = new HashMap<>();
        for (AbstractInsnNode insn : m.instructions)
            if (insn instanceof LabelNode label && referenced.contains(label) && !labels.containsKey(label))
                labels.put(label, labels.size());

        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof LabelNode label && !labels.containsKey(label)) continue;   // nothing refers to it
            out.append("  ").append(insn(insn, labels))
               .append(typeAnnotations(insn.visibleTypeAnnotations, insn.invisibleTypeAnnotations)).append('\n');
        }
        for (TryCatchBlockNode t : m.tryCatchBlocks)
            out.append("  try L").append(labels.get(t.start)).append(" L").append(labels.get(t.end)).append(" L")
               .append(labels.get(t.handler)).append(' ').append(t.type)
               .append(typeAnnotations(t.visibleTypeAnnotations, t.invisibleTypeAnnotations)).append('\n');
        localVariableAnnotations(out, "visible", m.visibleLocalVariableAnnotations, labels);
        localVariableAnnotations(out, "invisible", m.invisibleLocalVariableAnnotations, labels);
    }

    private static void parameters(StringBuilder out, String kind, int annotable, List<AnnotationNode>[] annotations) {
        if (annotable == 0 && annotations == null) return;
        out.append("  ").append(kind).append(' ').append(annotable);
        if (annotations != null)
            for (int i = 0; i < annotations.length; i++) out.append(" p").append(i).append(annotationList(annotations[i]));
        out.append('\n');
    }

    private static void localVariableAnnotations(StringBuilder out, String kind, List<LocalVariableAnnotationNode> list,
                                                 Map<LabelNode, Integer> labels) {
        if (list == null) return;
        for (LocalVariableAnnotationNode a : list)
            out.append("  local ").append(kind).append(' ').append(typeAnnotation(a)).append(" start ")
               .append(a.start.stream().map(l -> "L" + labels.get(l)).toList()).append(" end ")
               .append(a.end.stream().map(l -> "L" + labels.get(l)).toList()).append(" slots ").append(a.index).append('\n');
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
        return "[" + String.join(", ", out) + "]";
    }

    private static String constant(Object value) {
        return switch (value) {
            case null -> "null";
            case Type t -> "Type:" + t.getDescriptor();
            case Handle h -> "Handle:" + handle(h);
            case ConstantDynamic d -> "Condy:" + d.getName() + d.getDescriptor() + " " + handle(d.getBootstrapMethod()) + " "
                    + constants(java.util.stream.IntStream.range(0, d.getBootstrapMethodArgumentCount())
                            .mapToObj(d::getBootstrapMethodArgument).toArray());
            case String s -> "String:" + s.length() + ":" + s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
            case Float f -> "Float:" + Integer.toHexString(Float.floatToRawIntBits(f));
            case Double d -> "Double:" + Long.toHexString(Double.doubleToRawLongBits(d));
            default -> value.getClass().getSimpleName() + ":" + value;   // Integer, Long, Short, Byte, Character, Boolean
        };
    }

    /** An annotation element value as ASM's tree holds it: enum {descriptor, name}, nested annotation, list, or constant. */
    private static String value(Object value) {
        return switch (value) {
            case String[] constant -> "Enum:" + constant[0] + "." + constant[1];
            case AnnotationNode annotation -> annotation(annotation);
            case List<?> list -> list.stream().map(ClassFingerprint::value).collect(Collectors.joining(", ", "{", "}"));
            default -> constant(value);
        };
    }

    private static String annotation(AnnotationNode a) {
        StringBuilder out = new StringBuilder("@").append(a.desc).append('(');
        if (a.values != null)
            for (int i = 0; i < a.values.size(); i += 2)
                out.append(i == 0 ? "" : ", ").append(a.values.get(i)).append('=').append(value(a.values.get(i + 1)));
        return out.append(')').toString();
    }

    private static String annotationList(List<? extends AnnotationNode> list) {
        return list == null ? "[]" : list.stream().map(ClassFingerprint::annotation).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        return (visible == null ? "" : " visible" + annotationList(visible))
             + (invisible == null ? "" : " invisible" + annotationList(invisible));
    }

    private static String typeAnnotation(TypeAnnotationNode a) {
        return "target " + Integer.toHexString(a.typeRef) + " path " + a.typePath + " " + annotation(a);
    }

    private static String typeAnnotations(List<TypeAnnotationNode> visible, List<TypeAnnotationNode> invisible) {
        return (visible == null ? "" : " visibleType" + visible.stream().map(ClassFingerprint::typeAnnotation).toList())
             + (invisible == null ? "" : " invisibleType" + invisible.stream().map(ClassFingerprint::typeAnnotation).toList());
    }

    private static String attributes(List<Attribute> attributes) {
        return attributes == null ? "" : " attributes" + attributes.stream().map(a -> a.type).toList();
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
