package dev.phoenium.chunk;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes an unsupported native failure boundary; passing this test is NOT compatibility acceptance.
 * Executes the locked scheduler/holder/token/Rx classes, with only a three-status workload and queued executor.
 */
final class C2meNativeFailureSettlementTest {
    @TempDir Path temporary;
    private static final String S = "com/ishland/flowsched/scheduler/";

    @Test void nativeBrokenActionSettlesBusyButLeavesRequestedFuturesPendingUntilDemandIsRemoved() throws Exception {
        try (var loader = provider()) {
            Class<?> statusType = loader.loadClass(S.replace('/', '.') + "ItemStatus");
            Class<?> holderType = loader.loadClass(S.replace('/', '.') + "ItemHolder");
            Class<?> single = loader.loadClass("io.reactivex.rxjava3.core.Completable");
            Object statuses = Array.newInstance(statusType, 3);
            Object dependencies = Array.newInstance(loader.loadClass(S.replace('/', '.') + "KeyStatusPair"), 0);
            Throwable exactFailure = new IllegalStateException("native DISK terminal failure");
            for (int i = 0; i < 3; i++) {
                int ordinal = i;
                Object status = Proxy.newProxyInstance(loader, new Class<?>[] {statusType}, (proxy, method, args) -> switch (method.getName()) {
                    case "ordinal" -> ordinal;
                    case "getAllStatuses" -> statuses;
                    case "getPrev" -> ordinal == 0 ? null : Array.get(statuses, ordinal - 1);
                    case "getNext" -> ordinal == 2 ? null : Array.get(statuses, ordinal + 1);
                    case "getDependencies", "getDependenciesToAdd", "getDependenciesToRemove" -> dependencies;
                    case "upgradeToThis" -> single.getMethod("error", Throwable.class).invoke(null, exactFailure);
                    case "postUpgradeToThis", "preDowngradeFromThis", "downgradeFromThis" -> single.getMethod("complete").invoke(null);
                    case "toString" -> new String[] {"UNLOADED", "DISK", "FULL"}[ordinal];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.toString());
                });
                Array.set(statuses, ordinal, status);
            }
            var queue = new ArrayDeque<Runnable>();
            Class<?> generated = loader.define(schedulerClass());
            generated.getField("statuses").set(null, statuses);
            generated.getField("executor").set(null, (Executor) queue::addLast);
            Object scheduler = generated.getConstructor().newInstance();
            Object disk = Array.get(statuses, 1), full = Array.get(statuses, 2);
            Object holder = generated.getMethod("addTicket", Object.class, statusType, Runnable.class)
                    .invoke(scheduler, "chunk", full, (Runnable) () -> fail("failed request invoked success callback"));
            CompletableFuture<?> diskFuture = (CompletableFuture<?>) holderType.getMethod("getFutureForStatus0", statusType).invoke(holder, disk);
            CompletableFuture<?> fullFuture = (CompletableFuture<?>) holderType.getMethod("getFutureForStatus0", statusType).invoke(holder, full);
            drain(queue);
            assertSame(exactFailure, generated.getField("failure").get(null), "provider handler must observe exact failure");
            assertEquals(2, ((Number) holderType.getMethod("getFlags").invoke(holder)).intValue() & 2);
            assertEquals(false, holderType.getMethod("isBusy").invoke(holder));
            assertNull(holderType.getMethod("changingStatusTo").invoke(holder));
            assertTrue(((CompletableFuture<?>) holderType.getMethod("getOpFuture").invoke(holder)).isDone());
            assertFalse(diskFuture.isDone(), "characterization: MARK_BROKEN does not terminally fail requested DISK");
            assertFalse(fullFuture.isDone(), "characterization: dependent FULL also remains pending");
            assertEquals(1, generated.getMethod("itemCount").invoke(scheduler));

            // A later request on the broken holder retains the same pending future, not a new action.
            Object external = loader.loadClass(S.replace('/', '.') + "ItemTicket$TicketType").getField("EXTERNAL").get(null);
            Class<?> ticketType = external.getClass();
            generated.getMethod("addTicket", Object.class, Object.class, statusType, Runnable.class)
                    .invoke(scheduler, "chunk", "second", full, (Runnable) () -> fail("broken retry succeeded"));
            drain(queue);
            assertSame(diskFuture, holderType.getMethod("getFutureForStatus0", statusType).invoke(holder, disk));
            assertFalse(diskFuture.isDone());
            generated.getMethod("removeTicket", Object.class, ticketType, Object.class, statusType)
                    .invoke(scheduler, "chunk", external, "second", full);
            generated.getMethod("removeTicket", Object.class, statusType).invoke(scheduler, "chunk", full);
            drain(queue);
            assertEquals(0, generated.getMethod("itemCount").invoke(scheduler));
            assertTrue(diskFuture.isCompletedExceptionally());
            assertSame(holderType.getField("UNLOADED_EXCEPTION").get(null), diskFuture.exceptionNow());
            assertNotSame(exactFailure, diskFuture.exceptionNow(), "unload cannot propagate the original terminal failure");
        }
    }

    private static void drain(ArrayDeque<Runnable> queue) {
        int iterations = 0;
        while (!queue.isEmpty()) {
            assertTrue(iterations++ < 1000, "native test scheduler failed to quiesce");
            queue.removeFirst().run();
        }
    }

    private ProviderLoader provider() throws Exception {
        var urls = new ArrayList<URL>();
        try (var outer = new JarFile("build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar")) {
            for (var entry : java.util.Collections.list(outer.entries())) {
                String name = entry.getName();
                if (!name.startsWith("META-INF/jars/") || !(name.contains("base-") || name.contains("rxjava-")
                        || name.contains("reactive-streams-") || name.contains("jctools-"))) continue;
                Path target = temporary.resolve(Path.of(name).getFileName());
                try (var input = outer.getInputStream(entry)) { Files.copy(input, target); }
                urls.add(target.toUri().toURL());
            }
        }
        return new ProviderLoader(urls.toArray(URL[]::new));
    }

    private static final class ProviderLoader extends URLClassLoader {
        ProviderLoader(URL[] urls) { super(urls, C2meNativeFailureSettlementTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = name.startsWith("com.ishland.flowsched.") ? findClass(name) : super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
        Class<?> define(byte[] bytes) { return defineClass("phoeniumtest.NativeFailureScheduler", bytes, 0, bytes.length); }
    }

    private static byte[] schedulerClass() {
        String self = "phoeniumtest/NativeFailureScheduler", parent = S + "StatusAdvancingScheduler";
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V25, ACC_PUBLIC | ACC_SUPER, self, null, parent, null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "statuses", "[L" + S + "ItemStatus;", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "executor", "Ljava/util/concurrent/Executor;", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "failure", "Ljava/lang/Throwable;", null, null).visitEnd();
        MethodVisitor m = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode(); m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESPECIAL, parent, "<init>", "()V", false); m.visitInsn(RETURN); finish(m);
        m = writer.visitMethod(ACC_PROTECTED, "getBackgroundExecutor", "()Ljava/util/concurrent/Executor;", null, null);
        m.visitCode(); m.visitFieldInsn(GETSTATIC, self, "executor", "Ljava/util/concurrent/Executor;"); m.visitInsn(ARETURN); finish(m);
        m = writer.visitMethod(ACC_PROTECTED, "getUnloadedStatus", "()L" + S + "ItemStatus;", null, null);
        m.visitCode(); m.visitFieldInsn(GETSTATIC, self, "statuses", "[L" + S + "ItemStatus;"); m.visitInsn(ICONST_0); m.visitInsn(AALOAD); m.visitInsn(ARETURN); finish(m);
        m = writer.visitMethod(ACC_PROTECTED, "makeContext", "(L" + S + "ItemHolder;L" + S + "ItemStatus;[L" + S + "KeyStatusPair;Z)Ljava/lang/Object;", null, null);
        m.visitCode(); m.visitVarInsn(ALOAD, 1); m.visitInsn(ARETURN); finish(m);
        m = writer.visitMethod(ACC_PROTECTED, "handleTransactionException", "(L" + S + "ItemHolder;L" + S + "ItemStatus;ZLjava/lang/Throwable;)L" + S + "ExceptionHandlingAction;", null, null);
        m.visitCode(); m.visitVarInsn(ALOAD, 4); m.visitFieldInsn(PUTSTATIC, self, "failure", "Ljava/lang/Throwable;");
        m.visitFieldInsn(GETSTATIC, S + "ExceptionHandlingAction", "MARK_BROKEN", "L" + S + "ExceptionHandlingAction;"); m.visitInsn(ARETURN); finish(m);
        writer.visitEnd(); return writer.toByteArray();
    }
    private static void finish(MethodVisitor method) { method.visitMaxs(0, 0); method.visitEnd(); }
}
