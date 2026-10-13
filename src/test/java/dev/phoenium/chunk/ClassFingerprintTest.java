package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClassFingerprint against a synthetic class that uses every element it renders (S9 FULLNONSELF-48 follow-up, owner
 * 2026-10-12): each meaningful change (invokedynamic and constant-dynamic bootstrap references, exception handling,
 * annotations of every kind and place, signatures, constants, members, nesting) changes the fingerprint; debug data and
 * class-file layout do not; and the rendering is deterministic (no identity hash codes).
 */
final class ClassFingerprintTest {
    private static final String METAFACTORY_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
            + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    private static final String CONDY_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)I";

    private static AnnotationNode annotation(String desc, Object... values) {
        AnnotationNode a = new AnnotationNode(desc);
        if (values.length > 0) a.values = new ArrayList<>(List.of(values));
        return a;
    }

    private static <T> List<T> list(T element) {
        return new ArrayList<>(List.of(element));
    }

    private static TypeAnnotationNode typeAnnotation(int typeRef, String path, String desc) {
        return new TypeAnnotationNode(typeRef, path == null ? null : TypePath.fromString(path), desc);
    }

    /** The probe: one class using every rendered element (never loaded, only written and read back). */
    private static ClassNode probe() {
        ClassNode c = new ClassNode();
        c.version = Opcodes.V21;
        c.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        c.name = "probe/Probe";
        c.signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Runnable;";
        c.superName = "java/lang/Object";
        c.interfaces = list("java/lang/Runnable");
        c.nestMembers = list("probe/Probe$Inner");
        c.permittedSubclasses = list("probe/Probe$Inner");
        c.innerClasses = list(new InnerClassNode("probe/Probe$Inner", "probe/Probe", "Inner", Opcodes.ACC_STATIC));
        c.visibleAnnotations = list(annotation("Lprobe/Visible;", "text", "a", "level", new String[] {"Lprobe/Level;", "HIGH"},
                "nested", annotation("Lprobe/Inner;", "n", 1), "numbers", new ArrayList<>(List.of(1, 2, 3)),
                "type", Type.getType("Ljava/lang/String;")));
        c.invisibleAnnotations = list(annotation("Lprobe/Invisible;"));
        c.visibleTypeAnnotations = list(typeAnnotation(TypeReference.newSuperTypeReference(0).getValue(), null, "Lprobe/OnInterface;"));
        RecordComponentNode component = new RecordComponentNode("x", "I", null);
        component.visibleAnnotations = list(annotation("Lprobe/Component;"));
        c.recordComponents = list(component);

        FieldNode field = new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "K", "I", null, 42);
        field.visibleAnnotations = list(annotation("Lprobe/Field;"));
        field.invisibleTypeAnnotations = list(typeAnnotation(TypeReference.newTypeReference(TypeReference.FIELD).getValue(), null,
                "Lprobe/FieldType;"));
        c.fields.add(field);

        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "call", "(Ljava/lang/String;)Ljava/lang/Runnable;",
                "(Ljava/lang/String;)Ljava/lang/Runnable;", new String[] {"java/io/IOException"});
        m.visibleParameterAnnotations = new List[] {list(annotation("Lprobe/Parameter;"))};
        m.visibleAnnotableParameterCount = 1;
        m.invisibleTypeAnnotations = list(typeAnnotation(TypeReference.newTypeReference(TypeReference.METHOD_RETURN).getValue(),
                null, "Lprobe/Return;"));
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode(), other = new LabelNode();
        m.instructions.add(start);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        TypeInsnNode cast = new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String");
        cast.visibleTypeAnnotations = list(typeAnnotation(TypeReference.newTypeArgumentReference(TypeReference.CAST, 0).getValue(),
                null, "Lprobe/Cast;"));
        m.instructions.add(cast);
        m.instructions.add(new InvokeDynamicInsnNode("run", "(Ljava/lang/String;)Ljava/lang/Runnable;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", METAFACTORY_DESC, false),
                Type.getMethodType("()V"), new Handle(Opcodes.H_INVOKESTATIC, "probe/Probe", "lambda$call$0", "(Ljava/lang/String;)V", false),
                Type.getMethodType("()V")));
        m.instructions.add(new LdcInsnNode(new ConstantDynamic("c", "I",
                new Handle(Opcodes.H_INVOKESTATIC, "probe/Probe", "bootstrap", CONDY_DESC, false), "argument")));
        m.instructions.add(new InsnNode(Opcodes.POP));
        m.instructions.add(new LdcInsnNode(1.5f));
        m.instructions.add(new InsnNode(Opcodes.POP));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        m.instructions.add(end);
        m.instructions.add(handler);
        m.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        m.instructions.add(other);
        m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        TryCatchBlockNode runtime = new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException");
        runtime.visibleTypeAnnotations = list(typeAnnotation(TypeReference.newTryCatchReference(0).getValue(), null, "Lprobe/Caught;"));
        m.tryCatchBlocks.add(runtime);
        m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, other, "java/lang/Error"));
        m.visibleLocalVariableAnnotations = list(new LocalVariableAnnotationNode(
                TypeReference.newTypeReference(TypeReference.LOCAL_VARIABLE).getValue(), null,
                new LabelNode[] {start}, new LabelNode[] {end}, new int[] {1}, "Lprobe/Local;"));
        m.maxStack = 4;
        m.maxLocals = 3;
        c.methods.add(m);

        MethodNode member = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()I", null, null);
        member.annotationDefault = 7;
        c.methods.add(member);
        return c;
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] probeWith(Consumer<ClassNode> change) {
        ClassNode node = probe();
        change.accept(node);
        return bytes(node);
    }

    private static MethodNode call(ClassNode node) {
        return node.methods.get(0);
    }

    private static <T extends AbstractInsnNode> T first(ClassNode node, Class<T> type) {
        for (AbstractInsnNode insn : call(node).instructions) if (type.isInstance(insn)) return type.cast(insn);
        throw new AssertionError("no " + type.getSimpleName());
    }

    private static InvokeDynamicInsnNode indy(ClassNode node) {
        return first(node, InvokeDynamicInsnNode.class);
    }

    private static ConstantDynamic condy(ClassNode node) {
        for (AbstractInsnNode insn : call(node).instructions)
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic d) return d;
        throw new AssertionError("no condy");
    }

    private static LdcInsnNode floatConstant(ClassNode node) {
        for (AbstractInsnNode insn : call(node).instructions)
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Float) return ldc;
        throw new AssertionError("no float constant");
    }

    private static void replaceCondy(ClassNode node, ConstantDynamic replacement) {
        for (AbstractInsnNode insn : call(node).instructions)
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic) ldc.cst = replacement;
    }

    private static LabelNode label(ClassNode node, int index) {
        int seen = 0;
        for (AbstractInsnNode insn : call(node).instructions)
            if (insn instanceof LabelNode label && seen++ == index) return label;
        throw new AssertionError("no label " + index);
    }

    private static List<Object> values(ClassNode node) {
        return node.visibleAnnotations.get(0).values;
    }

    /** Every meaningful change, by name. */
    private static Map<String, Consumer<ClassNode>> mutations() {
        Map<String, Consumer<ClassNode>> m = new LinkedHashMap<>();
        // invokedynamic and constant-dynamic bootstrap references
        m.put("indy name", n -> indy(n).name = "get");
        m.put("indy descriptor", n -> indy(n).desc = "(Ljava/lang/Object;)Ljava/lang/Runnable;");
        m.put("indy bootstrap owner", n -> indy(n).bsm = new Handle(Opcodes.H_INVOKESTATIC, "probe/OtherFactory", "metafactory",
                METAFACTORY_DESC, false));
        m.put("indy bootstrap name", n -> indy(n).bsm = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                "altMetafactory", METAFACTORY_DESC, false));
        m.put("indy bootstrap kind", n -> indy(n).bsm = new Handle(Opcodes.H_NEWINVOKESPECIAL, "java/lang/invoke/LambdaMetafactory",
                "metafactory", METAFACTORY_DESC, false));
        m.put("indy bootstrap interface flag", n -> indy(n).bsm = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                "metafactory", METAFACTORY_DESC, true));
        m.put("indy implementation method", n -> indy(n).bsmArgs[1] = new Handle(Opcodes.H_INVOKESTATIC, "probe/Probe",
                "lambda$call$1", "(Ljava/lang/String;)V", false));
        m.put("indy implementation kind", n -> indy(n).bsmArgs[1] = new Handle(Opcodes.H_INVOKEVIRTUAL, "probe/Probe",
                "lambda$call$0", "(Ljava/lang/String;)V", false));
        m.put("indy method type argument", n -> indy(n).bsmArgs[0] = Type.getMethodType("(I)V"));
        m.put("indy argument count", n -> indy(n).bsmArgs = java.util.Arrays.copyOf(indy(n).bsmArgs, 2));
        m.put("condy name", n -> replaceCondy(n, new ConstantDynamic("d", "I", condy(n).getBootstrapMethod(), "argument")));
        m.put("condy bootstrap", n -> replaceCondy(n, new ConstantDynamic("c", "I",
                new Handle(Opcodes.H_INVOKESTATIC, "probe/Probe", "bootstrap2", CONDY_DESC, false), "argument")));
        m.put("condy argument", n -> replaceCondy(n, new ConstantDynamic("c", "I", condy(n).getBootstrapMethod(), "other")));
        // exception handling
        m.put("caught type", n -> call(n).tryCatchBlocks.get(0).type = "java/lang/Exception");
        m.put("catch everything", n -> call(n).tryCatchBlocks.get(0).type = null);
        m.put("handler range", n -> call(n).tryCatchBlocks.get(0).end = label(n, 2));
        m.put("handler target", n -> call(n).tryCatchBlocks.get(0).handler = label(n, 3));
        m.put("handler order", n -> java.util.Collections.swap(call(n).tryCatchBlocks, 0, 1));
        m.put("handler removed", n -> call(n).tryCatchBlocks.remove(1));
        m.put("declared exceptions", n -> call(n).exceptions = list("java/lang/Exception"));
        // annotations, every kind and place
        m.put("annotation string value", n -> values(n).set(1, "b"));
        m.put("annotation enum value", n -> values(n).set(3, new String[] {"Lprobe/Level;", "LOW"}));
        m.put("annotation enum type", n -> values(n).set(3, new String[] {"Lprobe/Other;", "HIGH"}));
        m.put("nested annotation value", n -> values(n).set(5, annotation("Lprobe/Inner;", "n", 2)));
        m.put("nested annotation type", n -> values(n).set(5, annotation("Lprobe/Other;", "n", 1)));
        m.put("annotation array element", n -> values(n).set(7, new ArrayList<>(List.of(1, 2, 4))));
        m.put("annotation class value", n -> values(n).set(9, Type.getType("Ljava/lang/Object;")));
        m.put("annotation element name", n -> values(n).set(0, "label"));
        m.put("annotation element type", n -> values(n).set(1, 'a'));
        m.put("annotation retention", n -> {
            n.invisibleAnnotations.add(n.visibleAnnotations.remove(0));
        });
        m.put("invisible class annotation", n -> n.invisibleAnnotations.get(0).desc = "Lprobe/Hidden;");
        m.put("added class annotation", n -> n.visibleAnnotations.add(annotation("Lprobe/Added;")));
        m.put("class type annotation", n -> n.visibleTypeAnnotations.get(0).desc = "Lprobe/Other;");
        m.put("type annotation target", n -> n.visibleTypeAnnotations.get(0).typeRef = TypeReference.newSuperTypeReference(-1).getValue());
        m.put("type annotation path", n -> n.visibleTypeAnnotations.get(0).typePath = TypePath.fromString("["));
        m.put("record component annotation", n -> n.recordComponents.get(0).visibleAnnotations.get(0).desc = "Lprobe/Other;");
        m.put("field annotation", n -> n.fields.get(0).visibleAnnotations.get(0).desc = "Lprobe/Other;");
        m.put("field type annotation", n -> n.fields.get(0).invisibleTypeAnnotations.get(0).desc = "Lprobe/Other;");
        m.put("parameter annotation", n -> call(n).visibleParameterAnnotations[0].get(0).desc = "Lprobe/Other;");
        m.put("parameter annotation moved to invisible", n -> {
            call(n).invisibleParameterAnnotations = call(n).visibleParameterAnnotations;
            call(n).invisibleAnnotableParameterCount = 1;
            call(n).visibleParameterAnnotations = null;
            call(n).visibleAnnotableParameterCount = 0;
        });
        m.put("method type annotation", n -> call(n).invisibleTypeAnnotations.get(0).desc = "Lprobe/Other;");
        m.put("instruction type annotation", n -> first(n, TypeInsnNode.class).visibleTypeAnnotations.get(0).desc = "Lprobe/Other;");
        m.put("handler type annotation", n -> call(n).tryCatchBlocks.get(0).visibleTypeAnnotations.get(0).desc = "Lprobe/Other;");
        m.put("local variable annotation", n -> call(n).visibleLocalVariableAnnotations.get(0).desc = "Lprobe/Other;");
        m.put("local variable annotation slot", n -> call(n).visibleLocalVariableAnnotations.get(0).index.set(0, 2));
        m.put("local variable annotation range", n -> call(n).visibleLocalVariableAnnotations.get(0).end.set(0, label(n, 2)));
        m.put("annotation default", n -> n.methods.get(1).annotationDefault = 8);
        // signatures, constants, members, nesting
        m.put("class signature", n -> n.signature = "<T:Ljava/lang/Number;>Ljava/lang/Object;Ljava/lang/Runnable;");
        m.put("method signature", n -> call(n).signature = "<X:Ljava/lang/Object;>(Ljava/lang/String;)Ljava/lang/Runnable;");
        m.put("field constant", n -> n.fields.get(0).value = 43);
        m.put("float constant", n -> floatConstant(n).cst = 1.25f);
        m.put("float constant type", n -> floatConstant(n).cst = 1.5d);
        m.put("record component", n -> n.recordComponents.get(0).descriptor = "J");
        m.put("permitted subclass", n -> n.permittedSubclasses.set(0, "probe/Probe$Other"));
        m.put("nest member", n -> n.nestMembers.set(0, "probe/Probe$Other"));
        m.put("inner class flags", n -> n.innerClasses.get(0).access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        m.put("class version", n -> n.version = Opcodes.V17);
        m.put("deprecated", n -> call(n).access |= Opcodes.ACC_DEPRECATED);
        m.put("cast type", n -> first(n, TypeInsnNode.class).desc = "java/lang/Object");
        return m;
    }

    @Test void everyMeaningfulChangeChangesTheFingerprint() {
        String original = ClassFingerprint.of(bytes(probe()));
        List<String> missed = new ArrayList<>();
        for (Map.Entry<String, Consumer<ClassNode>> mutation : mutations().entrySet()) {
            byte[] mutated = probeWith(mutation.getValue());
            if (original.equals(ClassFingerprint.of(mutated))) missed.add(mutation.getKey());
        }
        assertEquals(List.of(), missed, "changes the fingerprint did not detect");
    }

    @Test void theProbeExercisesEveryRenderedElement() {
        String text = ClassFingerprint.canonical(bytes(probe()));
        for (String expected : List.of("indy run", "Condy:c", "Float:3fc00000", "try L", "Enum:Lprobe/Level;.HIGH",
                "@Lprobe/Inner;(n=Integer:1)", "{Integer:1, Integer:2, Integer:3}", "invisible[@Lprobe/Invisible;()]",
                "visibleType[target", "record x I", "visibleParameters 1 p0[@Lprobe/Parameter;()]", "local visible",
                "default Integer:7", "permitted [probe/Probe$Inner]", "field 25 K I null Integer:42"))
            assertTrue(text.contains(expected), "rendered: " + expected + "\n" + text);
    }

    @Test void debugDataAndLayoutDoNotChangeTheFingerprint() {
        byte[] plain = bytes(probe());
        String expected = ClassFingerprint.of(plain);
        byte[] withDebug = probeWith(node -> {                     // line numbers, local names, source file
            node.sourceFile = "Probe.java";
            node.sourceDebug = "SMAP";
            MethodNode m = call(node);
            LabelNode start = label(node, 0), end = label(node, 1);
            m.instructions.insert(start, new LineNumberNode(10, start));
            m.instructions.insertBefore(end, new LineNumberNode(11, start));
            m.localVariables = list(new LocalVariableNode("text", "Ljava/lang/String;", null, start, end, 1));
            m.parameters = list(new ParameterNode("text", Opcodes.ACC_FINAL));
        });
        ClassWriter copied = new ClassWriter(new ClassReader(plain), 0);   // copies the pool: a different layout from probe()
        new ClassReader(withDebug).accept(copied, 0);
        byte[] relaid = copied.toByteArray();
        assertNotEquals(ClassFingerprint.sha256(plain), ClassFingerprint.sha256(withDebug));
        assertNotEquals(ClassFingerprint.sha256(plain), ClassFingerprint.sha256(relaid));
        assertEquals(expected, ClassFingerprint.of(withDebug));
        assertEquals(expected, ClassFingerprint.of(relaid));
    }

    @Test void theRenderingIsDeterministic() {
        byte[] bytes = bytes(probe());
        String text = ClassFingerprint.canonical(bytes);
        assertEquals(text, ClassFingerprint.canonical(bytes(probe())), "an independently built identical class");
        assertFalse(java.util.regex.Pattern.compile("@[0-9a-f]{5,}").matcher(text).find(), "no identity hash code:\n" + text);
        assertFalse(text.contains("[Ljava.lang"), "no array toString:\n" + text);
    }
}
