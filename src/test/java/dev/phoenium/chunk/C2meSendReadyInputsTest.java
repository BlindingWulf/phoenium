package dev.phoenium.chunk;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

class C2meSendReadyInputsTest {
    @TempDir Path temporary;
    @Test void retainedPositionsDeclareOnlyReachedNeighborChunksAndRejectDrift() {
        var positions = new ArrayList<>(List.of(new BlockPos(15, 65, 8), new BlockPos(8, 65, 0)));
        Runnable original = () -> positions.size();
        var retained = C2meSendReadyInputs.removalPositions(original);
        assertEquals(positions, retained);
        assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(1, 0), new ChunkPos(0, -1)),
                C2meSendReadyInputs.footprint(ChunkPos.ZERO, retained));
        positions.clear(); assertEquals(2, retained.size());
        assertThrows(ChunkProtocolException.class, () -> C2meSendReadyInputs.removalPositions(() -> { }));
        assertThrows(ChunkProtocolException.class, () -> C2meSendReadyInputs.footprint(ChunkPos.ZERO, List.of(new BlockPos(16, 65, 0))));
    }

    @Test void exactNativePinWaitsWithoutCompletingRequestsAndReleasesAllDemand() throws Exception {
        try (var f = fixture()) {
            Object holder = f.request(ChunkPos.ZERO, f.full);
            f.drain();
            var nativeFuture = (CompletableFuture<?>) f.holder.getMethod("getFutureForStatus0", f.statusType).invoke(holder, f.full);
            var input = new C2meSendReadyInputs(f.scheduler, f.full, f.token(), Set.of(ChunkPos.ZERO, new ChunkPos(9, 9)));
            assertEquals(1, input.pinCount()); assertTrue(input.awaited()); assertFalse(input.ready().isDone());
            f.remove(ChunkPos.ZERO, f.full); f.drain();
            assertSame(holder, f.get(ChunkPos.ZERO));
            assertFalse(nativeFuture.isDone(), "input preparation must never settle the provider's requester");
            f.gate.complete(null); f.drain();
            assertTrue(input.ready().isDone()); input.validate(); input.commit();
            input.close(); input.close(); f.drain();
            assertEquals(0, f.schedulerType.getMethod("itemCount").invoke(f.scheduler));
            assertTrue(nativeFuture.isDone()); assertFalse(nativeFuture.isCompletedExceptionally());
        }
    }

    @Test void nativeFailureKeepsCauseAndPinsReleaseWithoutCallbacksUnderHolderMonitor() throws Exception {
        try (var f = fixture()) {
            Object holder = f.request(ChunkPos.ZERO, f.full); f.drain();
            var input = new C2meSendReadyInputs(f.scheduler, f.full, f.token(), Set.of(ChunkPos.ZERO));
            Throwable original = new IllegalStateException("native prerequisite failure");
            var readiness = input.ready();
            var locks = new ArrayList<Boolean>();
            readiness.whenComplete((v,t) -> locks.add(Thread.holdsLock(holder)));
            f.gate.completeExceptionally(original); f.drain();
            assertSame(original, readiness.exceptionNow()); assertEquals(List.of(false), locks);
            input.close(); f.remove(ChunkPos.ZERO, f.full); f.drain();
            assertEquals(0, f.schedulerType.getMethod("itemCount").invoke(f.scheduler));
        }
    }

    @Test void absentOrLightOnlyDemandIsNotPromotedAndLaterDemandCancelsBeforeEffect() throws Exception {
        try (var f = fixture()) {
            Object disk = Array.get(f.statuses, 1);
            f.request(ChunkPos.ZERO, disk); f.drain();
            Object token = f.token();
            var input = new C2meSendReadyInputs(f.scheduler, f.full, token, Set.of(ChunkPos.ZERO));
            assertEquals(0, input.pinCount()); assertTrue(input.ready().isDone());
            assertSame(disk, f.holder.getMethod("getTargetStatus").invoke(f.get(ChunkPos.ZERO)));
            f.remove(ChunkPos.ZERO, disk); f.request(ChunkPos.ZERO, f.full);
            assertThrows(CancellationException.class, input::commit);
            assertEquals(true, token.getClass().getMethod("isCancelled").invoke(token));
            input.close(); f.gate.complete(null); f.drain(); f.remove(ChunkPos.ZERO, f.full); f.drain();
        }
    }

    @Test void cancelledAndShutdownInputsCannotCommitAndReleasePins() throws Exception {
        try (var f = fixture()) {
            f.request(ChunkPos.ZERO, f.full); f.gate.complete(null); f.drain();
            Object token = f.token();
            var input = new C2meSendReadyInputs(f.scheduler, f.full, token, Set.of(ChunkPos.ZERO));
            token.getClass().getMethod("cancel").invoke(token);
            assertThrows(CancellationException.class, input::commit); input.close();
            var stopped = new C2meSendReadyInputs(f.scheduler, f.full, f.token(), Set.of(ChunkPos.ZERO));
            stopped.shutdown(); assertThrows(CancellationException.class, stopped::commit);
            var committed = new C2meSendReadyInputs(f.scheduler, f.full, f.token(), Set.of(ChunkPos.ZERO));
            committed.commit(); committed.shutdown();
            f.remove(ChunkPos.ZERO, f.full); f.drain();
            assertNotNull(f.get(ChunkPos.ZERO), "shutdown must not release a committing body's pins");
            committed.close(); f.drain();
            assertEquals(0, f.schedulerType.getMethod("itemCount").invoke(f.scheduler));
        }
    }

    @Test void realRxWaitsAsynchronouslyAndResumesOriginalBodyOnExactScheduler() throws Exception {
        try (var f = fixture()) {
            Class<?> rx = f.loader.loadClass("io.reactivex.rxjava3.core.Completable");
            Class<?> schedulerType = f.loader.loadClass("io.reactivex.rxjava3.core.Scheduler");
            Object scheduler = f.loader.loadClass("io.reactivex.rxjava3.schedulers.Schedulers")
                    .getMethod("from", Executor.class).invoke(null, (Executor) f.queue::addLast);
            var calls = new ArrayList<String>(); var ready = new CompletableFuture<Void>();
            Runnable original = () -> calls.add("body");
            Object source = rx.getMethod("fromRunnable", Runnable.class).invoke(null, original);
            Object provider = rx.getMethod("subscribeOn", schedulerType).invoke(source, scheduler);
            Object bound = C2meFullAccess.rebindSendReadyGraph(provider, (body, exact) -> {
                assertSame(scheduler, exact); assertSame(original, body);
                return C2meSendReadyRx.defer(provider, exact, () -> {
                    calls.add("prepare");
                    return new C2meSendReadyRx.Prepared(ready, body, () -> calls.add("close"));
                });
            });
            Object observer = rx.getMethod("test").invoke(bound);
            assertTrue(calls.isEmpty()); f.drain(); assertEquals(List.of("prepare"), calls);
            ready.complete(null); assertEquals(List.of("prepare"), calls);
            f.drain(); assertEquals(List.of("prepare", "body", "close"), calls);
            observer.getClass().getMethod("assertComplete").invoke(observer);
            observer.getClass().getMethod("assertNoErrors").invoke(observer);
        }
    }

    @Test void realRxDependencyFailureClosesWithoutBodyAndPreservesOriginalCause() throws Exception {
        try (var f = fixture()) {
            Class<?> rx = f.loader.loadClass("io.reactivex.rxjava3.core.Completable");
            Object scheduler = f.loader.loadClass("io.reactivex.rxjava3.schedulers.Schedulers")
                    .getMethod("from", Executor.class).invoke(null, (Executor) f.queue::addLast);
            Object anchor = rx.getMethod("complete").invoke(null);
            Throwable original = new IllegalArgumentException("exact failed dependency");
            var closed = new java.util.concurrent.atomic.AtomicInteger();
            Object graph = C2meSendReadyRx.defer(anchor, scheduler, () -> new C2meSendReadyRx.Prepared(
                    CompletableFuture.failedFuture(original), () -> fail("failed input executed SEND_READY"), closed::incrementAndGet));
            Object observer = rx.getMethod("test").invoke(graph); f.drain();
            observer.getClass().getMethod("assertError", Throwable.class).invoke(observer, original);
            assertEquals(1, closed.get());
        }
    }

    @Test void providerControlsAcknowledgeNativeCancellationAndNeverRelabelACommit() throws Exception {
        try (var f = fixture()) {
            Object token = f.token();
            var reason = new C2meProviderControlException(ChunkStageAdmission.Result.SUPERSEDED);
            var cancellation = C2meSendReadyInputs.control(token, reason);
            assertSame(reason, cancellation.getCause());
            assertEquals(true, token.getClass().getMethod("isCancelled").invoke(token));
            assertThrows(CancellationException.class, () -> C2meSendReadyInputs.commitToken(token));
            Object committed = f.token(); C2meSendReadyInputs.commitToken(committed);
            assertThrows(ChunkProtocolException.class, () -> C2meSendReadyInputs.control(committed, reason));
        }
    }

    private Fixture fixture() throws Exception { return new Fixture(temporary); }
    private static final String S = "com/ishland/flowsched/scheduler/";
    static final class Fixture implements AutoCloseable {
        final Loader loader; final Class<?> statusType, holder, schedulerType;
        final Object statuses, full, scheduler;
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        Fixture(Path temporary) throws Exception {
            var urls = new ArrayList<URL>();
            Path dir = Files.createTempDirectory(temporary, "provider");
            try (var jar = new JarFile("../C2ME-locked-settlement/build/patched/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar")) {
                for (var entry : Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (!name.startsWith("META-INF/jars/") || !(name.contains("base-") || name.contains("rxjava-") || name.contains("reactive-streams-"))) continue;
                    Path path = dir.resolve(Path.of(name).getFileName());
                    try (var input = jar.getInputStream(entry)) { Files.copy(input, path); }
                    urls.add(path.toUri().toURL());
                }
            }
            loader = new Loader(urls.toArray(URL[]::new));
            statusType = loader.loadClass(S.replace('/', '.') + "ItemStatus");
            holder = loader.loadClass(S.replace('/', '.') + "ItemHolder");
            statuses = Array.newInstance(statusType, 3);
            Class<?> completable = loader.loadClass("io.reactivex.rxjava3.core.Completable");
            Object empty = Array.newInstance(loader.loadClass(S.replace('/', '.') + "KeyStatusPair"), 0);
            for (int i=0; i<3; i++) {
                int ordinal = i;
                Array.set(statuses, i, java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{statusType}, (p,m,a) -> switch (m.getName()) {
                    case "ordinal" -> ordinal;
                    case "getAllStatuses" -> statuses;
                    case "getPrev" -> ordinal == 0 ? null : Array.get(statuses, ordinal-1);
                    case "getNext" -> ordinal == 2 ? null : Array.get(statuses, ordinal+1);
                    case "getDependencies", "getDependenciesToAdd", "getDependenciesToRemove" -> empty;
                    case "upgradeToThis" -> completable.getMethod("fromCompletionStage", CompletionStage.class).invoke(null, gate);
                    case "postUpgradeToThis", "preDowngradeFromThis", "downgradeFromThis" -> completable.getMethod("complete").invoke(null);
                    case "toString" -> "status"+ordinal;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p==a[0];
                    default -> throw new AssertionError(m.toString());
                }));
            }
            full = Array.get(statuses,2); schedulerType = loader.define(schedulerClass());
            schedulerType.getField("statuses").set(null,statuses);
            schedulerType.getField("executor").set(null,(Executor) queue::addLast);
            scheduler = schedulerType.getConstructor().newInstance();
        }
        Object token() throws Exception { return loader.loadClass(S.replace('/', '.')+"Cancellable").getConstructor().newInstance(); }
        Object request(ChunkPos key,Object status) throws Exception { return schedulerType.getMethod("addTicket",Object.class,statusType,Runnable.class).invoke(scheduler,key,status,null); }
        void remove(ChunkPos key,Object status) throws Exception { schedulerType.getMethod("removeTicket",Object.class,statusType).invoke(scheduler,key,status); }
        Object get(ChunkPos key) throws Exception { return schedulerType.getMethod("getHolder",Object.class).invoke(scheduler,key); }
        void drain() { int count=0; while(!queue.isEmpty()) { assertTrue(count++<1000); queue.removeFirst().run(); } }
        public void close() throws Exception { loader.close(); }
    }
    static final class Loader extends URLClassLoader {
        Loader(URL[] urls) { super(urls,C2meSendReadyInputsTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized(getClassLoadingLock(name)) {
                Class<?> loaded=findLoadedClass(name);
                if(loaded==null) loaded=name.startsWith("com.ishland.flowsched.") ? findClass(name) : super.loadClass(name,false);
                if(resolve) resolveClass(loaded); return loaded;
            }
        }
        Class<?> define(byte[] bytes) { return defineClass("phoeniumtest.SendScheduler",bytes,0,bytes.length); }
    }
    private static byte[] schedulerClass() {
        String self="phoeniumtest/SendScheduler", parent=S+"StatusAdvancingScheduler";
        var w=new ClassWriter(ClassWriter.COMPUTE_MAXS); w.visit(V25,ACC_PUBLIC|ACC_SUPER,self,null,parent,null);
        w.visitField(ACC_PUBLIC|ACC_STATIC,"statuses","[L"+S+"ItemStatus;",null,null).visitEnd();
        w.visitField(ACC_PUBLIC|ACC_STATIC,"executor","Ljava/util/concurrent/Executor;",null,null).visitEnd();
        var m=w.visitMethod(ACC_PUBLIC,"<init>","()V",null,null); m.visitCode(); m.visitVarInsn(ALOAD,0); m.visitMethodInsn(INVOKESPECIAL,parent,"<init>","()V",false); m.visitInsn(RETURN); m.visitMaxs(0,0); m.visitEnd();
        m=w.visitMethod(ACC_PROTECTED,"getBackgroundExecutor","()Ljava/util/concurrent/Executor;",null,null); m.visitCode(); m.visitFieldInsn(GETSTATIC,self,"executor","Ljava/util/concurrent/Executor;"); m.visitInsn(ARETURN); m.visitMaxs(0,0); m.visitEnd();
        m=w.visitMethod(ACC_PROTECTED,"getUnloadedStatus","()L"+S+"ItemStatus;",null,null); m.visitCode(); m.visitFieldInsn(GETSTATIC,self,"statuses","[L"+S+"ItemStatus;"); m.visitInsn(ICONST_0); m.visitInsn(AALOAD); m.visitInsn(ARETURN); m.visitMaxs(0,0); m.visitEnd();
        m=w.visitMethod(ACC_PROTECTED,"makeContext","(L"+S+"ItemHolder;L"+S+"ItemStatus;[L"+S+"KeyStatusPair;Z)Ljava/lang/Object;",null,null); m.visitCode(); m.visitVarInsn(ALOAD,1); m.visitInsn(ARETURN); m.visitMaxs(0,0); m.visitEnd();
        w.visitEnd(); return w.toByteArray();
    }
}
