package dev.phoenium.core;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * S9-D13: a resolved public provider method (C2ME's, which Phoenium does not link against) bound once as a direct
 * call. {@link #invoke} answers as {@link Method#invoke} does for zero- and one-parameter methods: a missing
 * instance receiver is a NullPointerException, a receiver or argument of another type or a wrong argument count an
 * IllegalArgumentException, and anything the method itself throws arrives wrapped in an InvocationTargetException.
 * Unlike Method.invoke it allocates no argument array, runs no per-call access check, and lets the JIT inline the
 * provider method into the caller.
 */
public final class BoundMethod {
    // The spun call classes are defined beside the caller's lookup class, so their shapes are public.
    @FunctionalInterface public interface Instance0 { Object call(Object receiver) throws Throwable; }
    @FunctionalInterface public interface Instance1 { Object call(Object receiver, Object argument) throws Throwable; }
    @FunctionalInterface public interface Static0 { Object call() throws Throwable; }
    @FunctionalInterface public interface Static1 { Object call(Object argument) throws Throwable; }

    private final Method method;
    private final boolean isStatic;
    private final Class<?> receiverType;
    private final int parameterCount;
    private final Class<?> parameterType, boxedParameterType;
    private final Instance0 instance0; private final Instance1 instance1;
    private final Static0 static0; private final Static1 static1;

    private BoundMethod(MethodHandles.Lookup lookup, Method method) throws IllegalAccessException {
        this.method = method;
        isStatic = Modifier.isStatic(method.getModifiers());
        receiverType = method.getDeclaringClass();
        parameterCount = method.getParameterCount();
        if (parameterCount > 1) throw new IllegalArgumentException("bound methods take at most one argument: " + method);
        parameterType = parameterCount == 1 ? method.getParameterTypes()[0] : null;
        boxedParameterType = parameterType == null ? null : MethodType.methodType(parameterType).wrap().returnType();
        MethodHandle target = lookup.unreflect(method);
        Instance0 i0 = null; Instance1 i1 = null; Static0 s0 = null; Static1 s1 = null;
        if (isStatic && parameterCount == 0) {
            s0 = bind(lookup, target, Static0.class, MethodType.methodType(Object.class));
            if (s0 == null) { MethodHandle h = target.asType(MethodType.methodType(Object.class)); s0 = () -> (Object) h.invokeExact(); }
        } else if (isStatic) {
            s1 = bind(lookup, target, Static1.class, MethodType.methodType(Object.class, boxedParameterType));
            if (s1 == null) {
                MethodHandle h = target.asType(MethodType.methodType(Object.class, Object.class));
                s1 = argument -> (Object) h.invokeExact(argument);
            }
        } else if (parameterCount == 0) {
            i0 = bind(lookup, target, Instance0.class, MethodType.methodType(Object.class, receiverType));
            if (i0 == null) {
                MethodHandle h = target.asType(MethodType.methodType(Object.class, Object.class));
                i0 = receiver -> (Object) h.invokeExact(receiver);
            }
        } else {
            i1 = bind(lookup, target, Instance1.class, MethodType.methodType(Object.class, receiverType, boxedParameterType));
            if (i1 == null) {
                MethodHandle h = target.asType(MethodType.methodType(Object.class, Object.class, Object.class));
                i1 = (receiver, argument) -> (Object) h.invokeExact(receiver, argument);
            }
        }
        instance0 = i0; instance1 = i1; static0 = s0; static1 = s1;
    }

    /**
     * Binds a zero- or one-parameter method resolved by reflection, with the access the caller's own lookup has
     * (exactly what Method.invoke from that caller may call); an inaccessible method is an IllegalAccessException.
     */
    public static BoundMethod of(MethodHandles.Lookup caller, Method method) throws IllegalAccessException {
        return new BoundMethod(Objects.requireNonNull(caller, "caller"), Objects.requireNonNull(method, "method"));
    }

    public Method method() { return method; }

    /** {@code method.invoke(receiver)}; the receiver is ignored for a static method. */
    public Object invoke(Object receiver) throws IllegalAccessException, InvocationTargetException {
        if (parameterCount != 0) throw new IllegalArgumentException("wrong number of arguments: 0 expected: " + parameterCount);
        if (!isStatic) requireReceiver(receiver);
        try { return isStatic ? static0.call() : instance0.call(receiver); }
        catch (Throwable thrown) { throw new InvocationTargetException(thrown); }
    }

    /** {@code method.invoke(receiver, argument)}; the receiver is ignored for a static method. */
    public Object invoke(Object receiver, Object argument) throws IllegalAccessException, InvocationTargetException {
        if (parameterCount != 1) throw new IllegalArgumentException("wrong number of arguments: 1 expected: " + parameterCount);
        if (!isStatic) requireReceiver(receiver);
        if (argument == null ? parameterType.isPrimitive() : !boxedParameterType.isInstance(argument))
            throw new IllegalArgumentException("argument type mismatch");
        try { return isStatic ? static1.call(argument) : instance1.call(receiver, argument); }
        catch (Throwable thrown) { throw new InvocationTargetException(thrown); }
    }

    private void requireReceiver(Object receiver) {
        if (receiver == null) throw new NullPointerException("Cannot invoke " + method + " on a null receiver");
        if (!receiverType.isInstance(receiver)) throw new IllegalArgumentException("object is not an instance of declaring class");
    }

    /**
     * The direct call, or null where the metafactory cannot spin this provider shape (an exact handle call then).
     * The spun class names the method's declaring, parameter and return types, which resolve through the caller's
     * class loader at the first call; a type that loader does not see (a class from another loader) keeps the
     * handle call, which names no type.
     */
    private static <T> T bind(MethodHandles.Lookup lookup, MethodHandle target, Class<T> shape, MethodType instantiated) {
        Method sam = shape.getDeclaredMethods()[0];
        MethodType erased = MethodType.methodType(sam.getReturnType(), sam.getParameterTypes());
        ClassLoader loader = lookup.lookupClass().getClassLoader();
        MethodType direct = target.type();
        if (!visible(loader, direct.returnType())) return null;
        for (Class<?> type : direct.parameterArray()) if (!visible(loader, type)) return null;
        try {
            return shape.cast(LambdaMetafactory.metafactory(lookup, sam.getName(), MethodType.methodType(shape), erased,
                target, instantiated).getTarget().invoke());
        } catch (Throwable unavailable) { return null; }
    }
    private static boolean visible(ClassLoader loader, Class<?> type) {
        while (type.isArray()) type = type.getComponentType();
        if (type.isPrimitive()) return true;
        try { return Class.forName(type.getName(), false, loader) == type; }
        catch (ClassNotFoundException | LinkageError invisible) { return false; }
    }
}
