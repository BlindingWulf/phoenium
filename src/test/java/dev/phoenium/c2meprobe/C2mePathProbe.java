package dev.phoenium.c2meprobe;

import com.google.gson.GsonBuilder;
import dev.phoenium.ownership.OwnershipContext;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Read-only bounded receipts. Never supplies demand, results, authority or save work. */
public final class C2mePathProbe implements DedicatedServerModInitializer {
    private static final Map<String, Receipt> COUNTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean DELAY_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean FAILURE_USED = new AtomicBoolean();
    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static final AtomicBoolean HALT_SCHEDULED = new AtomicBoolean();
    private static final AtomicBoolean CANCEL_SCHEDULED = new AtomicBoolean();
    private static final Map<Object, Object> READ_CANCELLABLES = new ConcurrentHashMap<>();
    private record FailedHolder(Object holder, CompletableFuture<?> future, Throwable cause) { }
    private static final Map<Object, FailedHolder> FAILED_HOLDERS = new ConcurrentHashMap<>();
    private static final Set<Object> RETIRED_FAILED_HOLDERS = ConcurrentHashMap.newKeySet();
    private static volatile MinecraftServer SERVER;
    private static final class Receipt {
        final LongAdder calls = new LongAdder(), owned = new LongAdder();
        final String firstThread = Thread.currentThread().getName();
        final String firstOwner = OwnershipContext.current().map(c -> c.region().toString()).orElse("NONE");
        final List<String> firstStack = StackWalker.getInstance().walk(s -> s.limit(28).map(Object::toString).toList());
    }
    public static void hit(String key) {
        if (key.contains(".promoteChunkMap")) {
            boolean callback = StackWalker.getInstance().walk(s -> s.limit(12)
                .anyMatch(f -> f.getMethodName().endsWith("$updateHolderMapAfterTick")));
            if (callback) key += ".fromC2meTickCallback";
        }
        var receipt = COUNTS.computeIfAbsent(key, ignored -> new Receipt());
        receipt.calls.increment();
        if (OwnershipContext.current().isPresent()) receipt.owned.increment();
        perturb(key);
    }

    /** Captures only the exact ReadFromDiskAsync action token for the disposable cancellation witness. */
    public static void captureReadCancellable(Object context, Object cancellable) {
        if (context != null && cancellable != null) READ_CANCELLABLES.put(context, cancellable);
        if (Boolean.getBoolean("phoenium.c2me.nativeSettlementWitness")) captureNativeRequesters(context, cancellable);
    }

    /** Observes provider-owned promises; never completes them or changes native demand. */
    private static void captureNativeRequesters(Object context, Object token) {
        try {
            Object holder = context.getClass().getMethod("holder").invoke(context);
            Object key = holder.getClass().getMethod("getKey").invoke(holder);
            ClassLoader loader = context.getClass().getClassLoader();
            Class<?> statusType = Class.forName("com.ishland.flowsched.scheduler.ItemStatus", false, loader);
            Class<?> statuses = Class.forName("com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus", false, loader);
            Object disk = statuses.getField("DISK").get(null);
            Object full = statuses.getField("SERVER_ACCESSIBLE").get(null);
            var getter = holder.getClass().getMethod("getFutureForStatus0", statusType);
            Object target = holder.getClass().getMethod("getTargetStatus").invoke(holder);
            int targetOrdinal = (int) statusType.getMethod("ordinal").invoke(target);
            for (Object status : new Object[] {disk, full}) {
                int ordinal = (int) statusType.getMethod("ordinal").invoke(status);
                if (ordinal > targetOrdinal) continue;
                String label = status == disk ? "DISK" : "FULL";
                var future = (CompletableFuture<?>) getter.invoke(holder, status);
                if (status == disk) System.out.println("C2ME_NATIVE_ACTION_START key=" + key
                        + " context=" + System.identityHashCode(context) + " token=" + System.identityHashCode(token)
                        + " holder=" + System.identityHashCode(holder) + " slot=" + System.identityHashCode(future));
                hit("native.requested." + label);
                future.whenComplete((value, error) -> {
                    hit("native.terminal." + label + "." + (error == null ? "SUCCESS" : "ERROR"));
                    if (status == disk) READ_CANCELLABLES.remove(context, token);
                    if (error != null) {
                        Throwable cause = error;
                        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                        if (status == disk && containsInjectedFailure(cause))
                            FAILED_HOLDERS.putIfAbsent(key, new FailedHolder(holder, future, error));
                        System.out.println("C2ME_NATIVE_REQUESTER label=" + label + " context=" + System.identityHashCode(context)
                                + " token=" + System.identityHashCode(token) + " holder=" + System.identityHashCode(holder)
                                + " slot=" + System.identityHashCode(future) + " holderLock=" + Thread.holdsLock(holder)
                                + " cause=" + cause);
                        try {
                            var later = (CompletableFuture<?>) getter.invoke(holder, status);
                            System.out.println("C2ME_NATIVE_LATER label=" + label + " done=" + later.isDone()
                                    + " exceptional=" + later.isCompletedExceptionally()
                                    + " sameCause=" + (later.isCompletedExceptionally() && later.handle((v, t) -> t).join() == error));
                        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                    }
                });
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native witness contract drift", failure); }
    }

    private static boolean containsInjectedFailure(Throwable error) {
        if (error == null) return false;
        if (String.valueOf(error.getMessage()).contains("C2ME test injected failure")) return true;
        for (Throwable suppressed : error.getSuppressed()) if (containsInjectedFailure(suppressed)) return true;
        return error.getCause() != error && containsInjectedFailure(error.getCause());
    }

    public static Object observeNativeTerminal(Object completable, Object context, Object token) {
        try {
            ClassLoader loader = completable.getClass().getClassLoader();
            Class<?> consumer = Class.forName("io.reactivex.rxjava3.functions.Consumer", false, loader);
            Object callback = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {consumer}, (proxy, method, args) -> {
                if (method.getName().equals("accept")) { nativeActionTerminal(context, token, (Throwable) args[0]); return null; }
                if (method.getName().equals("toString")) return "native-terminal-observer";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                throw new UnsupportedOperationException(method.toString());
            });
            return Class.forName("io.reactivex.rxjava3.core.Completable", false, loader)
                    .getMethod("doOnEvent", consumer).invoke(completable, callback);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    public static void nativeActionTerminal(Object context, Object token, Throwable error) {
        if (!Boolean.getBoolean("phoenium.c2me.nativeSettlementWitness")) return;
        try {
            Object holder = context.getClass().getMethod("holder").invoke(context);
            Object key = holder.getClass().getMethod("getKey").invoke(holder);
            System.out.println("C2ME_NATIVE_ACTION_TERMINAL key=" + key + " context=" + System.identityHashCode(context)
                    + " token=" + System.identityHashCode(token) + " holder=" + System.identityHashCode(holder)
                    + " cause=" + error);
            FailedHolder old = FAILED_HOLDERS.get(key);
            if (error == null && old != null && old.holder() != holder) {
                boolean preserved = old.future().isCompletedExceptionally()
                        && old.future().handle((v, t) -> t).join() == old.cause();
                System.out.println("C2ME_NATIVE_REPLACEMENT_SUCCESS key=" + key
                        + " old=" + System.identityHashCode(old.holder()) + " new=" + System.identityHashCode(holder)
                        + " oldRetired=" + RETIRED_FAILED_HOLDERS.contains(old.holder()) + " oldCausePreserved=" + preserved);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    public static void nativeHolderRelease(Object holder) {
        if (!Boolean.getBoolean("phoenium.c2me.nativeSettlementWitness")) return;
        for (var entry : FAILED_HOLDERS.entrySet()) {
            if (entry.getValue().holder() == holder) {
                RETIRED_FAILED_HOLDERS.add(holder);
                System.out.println("C2ME_NATIVE_FAILED_HOLDER_RETIRED key=" + entry.getKey()
                        + " holder=" + System.identityHashCode(holder));
            }
        }
    }

    private static String cancelCapturedReads() {
        int attempted = 0, cancelled = 0, failures = 0;
        for (var entry : READ_CANCELLABLES.entrySet()) {
            attempted++;
            try {
                Object result = entry.getValue().getClass().getMethod("cancel").invoke(entry.getValue());
                if (Boolean.TRUE.equals(result)) {
                    cancelled++;
                    System.out.println("C2ME_TEST_CANCEL_TOKEN context=" + System.identityHashCode(entry.getKey())
                            + " token=" + System.identityHashCode(entry.getValue()));
                }
            } catch (Throwable failure) {
                failures++;
                System.out.println("C2ME_TEST_CANCEL_FAILURE " + failure);
            }
        }
        READ_CANCELLABLES.clear();
        return "C2ME_TEST_CANCEL attempted=" + attempted + " cancelled=" + cancelled + " failures=" + failures;
    }

    /**
     * Disposable-lab controls only.  They are deliberately implemented in the observer JAR,
     * never in Phoenium production, so a lifetime run can hold the locked provider at a named
     * boundary long enough for an ordinary unload/stop command to race it.  The failure switch is
     * one-shot to avoid converting a bounded provider fault into an endless retry storm.
     */
    private static void perturb(String key) {
        String failureKey = System.getProperty("phoenium.c2me.failKey", "");
        if (ARMED.get() && !failureKey.isBlank() && matches(key, failureKey)
                && FAILURE_USED.compareAndSet(false, true)) {
            System.out.println("C2ME_TEST_INJECTED_FAILURE key=" + key);
            throw new RuntimeException("C2ME test injected failure at " + key);
        }
        String delayKey = System.getProperty("phoenium.c2me.delayKey", "");
        long delayMillis = Long.getLong("phoenium.c2me.delayMs", 0L);
        if (!ARMED.get() || delayKey.isBlank() || delayMillis <= 0 || !matches(key, delayKey)
                || Thread.currentThread().getName().equals("Server thread")) return;
        long boundedMillis = Math.min(delayMillis, 15_000L);
        if (!DELAY_REPORTED.compareAndSet(false, true)) return;
        System.out.println("C2ME_TEST_DELAY key=" + key + " millis=" + boundedMillis
                + " thread=" + Thread.currentThread().getName());
        LockSupport.parkNanos(boundedMillis * 1_000_000L);
    }

    private static boolean matches(String key, String requested) {
        if (requested.endsWith(".enter"))
            return key.endsWith(".enter") && key.contains(requested.substring(0, requested.length() - 6));
        if (requested.endsWith(".return"))
            return key.endsWith(".return") && key.contains(requested.substring(0, requested.length() - 7));
        return key.contains(requested);
    }

    /** Inject within the native task loop, preserving its aggregate-and-clear semantics. */
    public static void injectDrainTask(Object queue) {
        if (!ARMED.get() || !System.getProperty("phoenium.c2me.failKey", "").equals("drainQueue.task")) return;
        @SuppressWarnings("unchecked") var tasks = (List<Runnable>) queue;
        if (tasks.isEmpty() || !FAILURE_USED.compareAndSet(false, true)) return;
        tasks.addFirst(() -> {
            System.out.println("C2ME_TEST_INJECTED_FAILURE key=drainQueue.task");
            throw new RuntimeException("C2ME test injected failure at drainQueue.task");
        });
    }
    public static void result(boolean value) { hit("distance.result." + value); }
    private static void snapshot(Path output) {
        try {
            Map<String, Object> values = new TreeMap<>();
            COUNTS.forEach((key, r) -> values.put(key, Map.of("calls", r.calls.sum(), "owned", r.owned.sum(),
                "firstThread", r.firstThread, "firstOwner", r.firstOwner, "firstStack", r.firstStack)));
            Path pending = output.resolveSibling(output.getFileName() + ".pending");
            Files.writeString(pending, new GsonBuilder().setPrettyPrinting().create().toJson(values));
            Files.move(pending, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { System.err.println("C2ME_PROBE_WRITE_FAILED " + failure); }
    }
    @Override public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                Commands.literal("c2meprobe").then(Commands.literal("arm").executes(context -> {
                    ARMED.set(true);
                    scheduleExternalHalt();
                    scheduleExternalCancel();
                    context.getSource().sendSuccess(() -> Component.literal("C2ME_TEST_ARMED"), false);
                    return 1;
                })).then(Commands.literal("cancel").executes(context -> {
                    String receipt = cancelCapturedReads();
                    System.out.println(receipt);
                    context.getSource().sendSuccess(() -> Component.literal(receipt), false);
                    return 1;
                }))));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> SERVER = server);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVER = null);
        Path output = Path.of(System.getProperty("phoenium.c2me.observations"));
        var executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "phoenium-c2me-observer"); thread.setDaemon(true); return thread;
        });
        executor.scheduleAtFixedRate(() -> snapshot(output), 0, 1, TimeUnit.SECONDS);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { executor.shutdown(); snapshot(output); });
    }

    private static void scheduleExternalHalt() {
        long delayMillis = Long.getLong("phoenium.c2me.haltAfterMs", 0L);
        if (delayMillis <= 0 || !HALT_SCHEDULED.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon(true).name("phoenium-c2me-test-halt").start(() -> {
            try {
                Thread.sleep(Math.min(delayMillis, 15_000L));
                MinecraftServer server = SERVER;
                if (server != null) {
                    System.out.println("C2ME_TEST_HALT_REQUEST delayMs=" + Math.min(delayMillis, 15_000L));
                    server.halt(false);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void scheduleExternalCancel() {
        long delayMillis = Long.getLong("phoenium.c2me.cancelAfterMs", 0L);
        if (delayMillis <= 0 || !CANCEL_SCHEDULED.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon(true).name("phoenium-c2me-test-cancel").start(() -> {
            try {
                Thread.sleep(Math.min(delayMillis, 15_000L));
                String receipt = cancelCapturedReads();
                System.out.println("C2ME_TEST_CANCEL_REQUEST delayMs=" + Math.min(delayMillis, 15_000L));
                System.out.println(receipt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
