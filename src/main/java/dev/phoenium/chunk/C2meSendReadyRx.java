package dev.phoenium.chunk;

import java.lang.reflect.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Composes finite asynchronous prerequisites around the retained original provider Runnable. */
final class C2meSendReadyRx {
    record Prepared(CompletionStage<Void> ready, Runnable body, Runnable close) { }
    private C2meSendReadyRx() { }

    static Object defer(Object anchor, Object scheduler, Supplier<Prepared> prepare) {
        try {
            ClassLoader loader = anchor.getClass().getClassLoader();
            Class<?> completable = loader.loadClass("io.reactivex.rxjava3.core.Completable");
            Class<?> supplier = loader.loadClass("io.reactivex.rxjava3.functions.Supplier");
            Object deferred = Proxy.newProxyInstance(loader, new Class<?>[] {supplier}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                Prepared prepared = prepare.get();
                try {
                    Object readiness = call(completable.getMethod("fromCompletionStage", CompletionStage.class), null, prepared.ready());
                    Object resumed = call(completable.getMethod("observeOn", loader.loadClass("io.reactivex.rxjava3.core.Scheduler")), readiness, scheduler);
                    Object body = call(completable.getMethod("fromRunnable", Runnable.class), null, prepared.body());
                    Object composed = call(completable.getMethod("andThen", loader.loadClass("io.reactivex.rxjava3.core.CompletableSource")), resumed, body);
                    Class<?> consumer = loader.loadClass("io.reactivex.rxjava3.functions.Consumer");
                    Object terminal = Proxy.newProxyInstance(loader, new Class<?>[] {consumer}, (p, m, a) -> {
                        if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, a);
                        try { prepared.close().run(); }
                        catch (Throwable cleanup) {
                            Throwable original = (Throwable) a[0];
                            if (original == null) throw cleanup;
                            if (original != cleanup) original.addSuppressed(cleanup);
                        }
                        return null;
                    });
                    return call(completable.getMethod("doOnEvent", consumer), composed, terminal);
                } catch (Throwable failure) { prepared.close().run(); throw failure; }
            });
            return call(completable.getMethod("defer", supplier), null, deferred);
        } catch (Throwable failure) { return C2meSendReadyRx.<RuntimeException, Object>raise(failure); }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "PhoeniumSendReady";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new IllegalStateException(method.toString());
        };
    }
    private static Object call(Method method, Object receiver, Object... args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable, R> R raise(Throwable failure) throws T { throw (T) failure; }
}
