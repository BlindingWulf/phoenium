package dev.phoenium.chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

/** Controlled environment endpoints only; no production hooks or authority. */
final class FullDecisionWitnessSupport {
    record Answer(Method method, Function<Object[], Object> function) { }
    private static int sequence;
    static <T> T stub(Class<T> parent, List<Answer> answers) throws Exception {
        String name = "dev/phoenium/chunk/DecisionEnvironment" + ++sequence;
        Type generated = Type.getObjectType(name), functions = Type.getType(Function[].class);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, Type.getInternalName(parent), null);
        writer.visitField(Opcodes.ACC_PUBLIC, "functions", functions.getDescriptor(), null, null).visitEnd();
        for (int i = 0; i < answers.size(); i++) {
            Method method = answers.get(i).method();
            var generator = new GeneratorAdapter(Opcodes.ACC_PUBLIC,
                    new org.objectweb.asm.commons.Method(method.getName(), Type.getMethodDescriptor(method)), null, null, writer);
            generator.loadThis(); generator.getField(generated, "functions", functions);
            generator.push(i); generator.arrayLoad(Type.getType(Function.class)); generator.loadArgArray();
            generator.invokeInterface(Type.getType(Function.class), new org.objectweb.asm.commons.Method("apply", "(Ljava/lang/Object;)Ljava/lang/Object;"));
            if (method.getReturnType() == void.class) generator.pop();
            else generator.unbox(Type.getType(method.getReturnType()));
            generator.returnValue(); generator.endMethod();
        }
        writer.visitEnd();
        Class<?> type = new ClassLoader(parent.getClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), writer.toByteArray(), 0, writer.toByteArray().length); }
        }.define();
        T value = parent.cast(allocate(type));
        type.getField("functions").set(value, answers.stream().map(Answer::function).toArray(Function[]::new));
        return value;
    }
    static <T> T allocate(Class<T> type) throws Exception { return type.cast(MinecraftLightReadFixture.allocate(type)); }
    static void set(Object instance, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    static Object field(Object instance, Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
}
