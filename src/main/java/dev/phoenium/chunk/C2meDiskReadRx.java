package dev.phoenium.chunk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/** Reflection is confined to the optional locked Rx provider surface. */
public final class C2meDiskReadRx {
    private C2meDiskReadRx() { }
    @FunctionalInterface private interface Body { Object call(Object value) throws Throwable; }

    public static Object guardRecovery(Object function, C2meDiskReadOperation operation) {
        return proxy(function, "Function", value -> {
            Throwable failure = unwrap((Throwable) value);
            try { operation.check(); }
            catch (Throwable terminal) { return errorSingle(function, terminal); }
            if (operation.mustBypassRecovery(failure)) return errorSingle(function, failure);
            return invokeFunction(function, value);
        });
    }

    public static Object guardRetry(Object function, C2meDiskReadOperation operation) {
        return proxy(function, "Function", errors -> {
            Object guard = proxy(function, "Function", value -> {
                Throwable failure = unwrap((Throwable) value);
                operation.check();
                if (operation.mustBypassRecovery(failure)) throw failure;
                return value;
            });
            Class<?> flowable = type(function, "core.Flowable");
            Object filtered = invoke(flowable.getMethod("map", type(function, "functions.Function")), errors, guard);
            return invokeFunction(function, filtered);
        });
    }

    public static Object guardFailureReport(Object consumer, C2meDiskReadOperation operation) {
        return proxy(consumer, "Consumer", value -> {
            if (unwrap((Throwable) value) instanceof C2meDiskReadOperation.Control) return null;
            return invoke(type(consumer, "functions.Consumer").getMethod("accept", Object.class), consumer, value);
        });
    }

    public static Object onTerminal(Object completable, Consumer<Throwable> callback) {
        try {
            Object consumer = proxy(completable, "Consumer", value -> { callback.accept(unwrap((Throwable) value)); return null; });
            return invoke(type(completable, "core.Completable").getMethod("doOnEvent", type(completable, "functions.Consumer")),
                    completable, consumer);
        } catch (Throwable failure) { return throwUnchecked(failure); }
    }

    private static Object proxy(Object anchor, String name, Body body) {
        Class<?> contract = type(anchor, "functions." + name);
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "PhoeniumC2meDisk" + name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new IllegalStateException(method.toString());
            };
            return body.call(args[0]);
        });
    }

    private static Object errorSingle(Object anchor, Throwable failure) throws Throwable {
        return invoke(type(anchor, "core.Single").getMethod("error", Throwable.class), null, failure);
    }

    private static Object invokeFunction(Object function, Object value) throws Throwable {
        return invoke(type(function, "functions.Function").getMethod("apply", Object.class), function, value);
    }
    private static Class<?> type(Object anchor, String name) {
        try { return Class.forName("io.reactivex.rxjava3." + name, false, anchor.getClass().getClassLoader()); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("locked Rx surface missing", failure); }
    }
    private static Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    public static Throwable unwrap(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R throwUnchecked(Throwable failure) throws T { throw (T) failure; }
}
