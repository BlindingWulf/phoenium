package dev.phoenium.chunk;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S9-M6b (MEM-6, D40): FlowSchedQueuePurge against the real FlowSched queue, task and executor classes of the pinned C2ME
 * build (loaded from its nested base jar; class hashes pinned in C2meScheduledTaskReleaseBinaryTest). Queue-only tests
 * model the executor's accounting exactly: one permit released per entry {@code requeue} enqueued, one acquired per
 * entry a worker polls.
 */
final class FlowSchedQueuePurgeTest {
    private static final Path C2ME = Path.of("build", "stage6-audit-20260909", "research", "artifacts", "c2me",
            "c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar");
    private static final String BASE = "META-INF/jars/c2me-fabric-base-mc26.2-0.4.2-alpha.0.43-all.jar";
    private static final int BUCKETS = 64, LOWEST = 45;

    private static Path baseJar;
    private static URLClassLoader loader;
    private static Constructor<?> newQueue, newTask;
    private static Method requeue, dequeue, runTask;
    private static Field buckets, counts;
    private static Object tombstone;

    @BeforeAll static void loadFlowSched() throws Exception {
        baseJar = Files.createTempFile("c2me-base", ".jar");
        try (JarFile outer = new JarFile(C2ME.toFile())) {
            Files.write(baseJar, outer.getInputStream(outer.getEntry(BASE)).readAllBytes());
        }
        loader = new URLClassLoader(new URL[] { baseJar.toUri().toURL() }, FlowSchedQueuePurgeTest.class.getClassLoader());
        Class<?> queue = loader.loadClass("com.ishland.flowsched.executor.BucketTaskPriorityQueue");
        Class<?> task = loader.loadClass("com.ishland.flowsched.executor.Task");
        newQueue = queue.getConstructor(int.class);
        newTask = loader.loadClass("com.ishland.flowsched.executor.SimpleTask").getConstructor(Runnable.class);
        requeue = queue.getMethod("requeue", task, int.class);
        dequeue = queue.getMethod("dequeue");
        runTask = task.getMethod("run", Runnable.class);
        buckets = queue.getDeclaredField("priorities");
        counts = queue.getDeclaredField("taskCount");
        buckets.setAccessible(true);
        counts.setAccessible(true);
        tombstone = task.getField("TOMBSTONE").get(null);
    }

    @AfterAll static void close() throws Exception {
        loader.close();
        Files.deleteIfExists(baseJar);
    }

    /** A BucketTaskPriorityQueue with the executor's permit accounting (ExecutorManager.reschedule0 / WorkerThread). */
    private static final class Sim {
        final Object queue;
        final Semaphore permits = new Semaphore(0);
        final ConcurrentLinkedQueue<?>[] all;
        final AtomicIntegerArray count;

        Sim() throws Exception {
            queue = newQueue.newInstance(BUCKETS);
            all = (ConcurrentLinkedQueue<?>[]) buckets.get(queue);
            count = (AtomicIntegerArray) counts.get(queue);
        }

        void enqueue(Object task, int priority) throws Exception {
            if ((boolean) requeue.invoke(queue, task, priority)) permits.release();
        }

        /** As WorkerThread: take a permit, then poll until an entry (task or TOMBSTONE) comes out; null if no permit. */
        Object poll() throws Exception {
            if (!permits.tryAcquire()) return null;
            for (;;) {
                Object entry = dequeue.invoke(queue);
                if (entry != null) return entry;
                Thread.onSpinWait();
            }
        }

        int compact(int bucket) throws Exception {
            return FlowSchedQueuePurge.compact(all[bucket], count, bucket, priority(), permits);
        }

        int entries() {
            int n = 0;
            for (ConcurrentLinkedQueue<?> q : all) n += q.size();
            return n;
        }
    }

    @Test void hookTargetsMatchThePinnedExecutor() throws Exception {
        ClassNode queue = node("com/ishland/flowsched/executor/BucketTaskPriorityQueue");
        assertField(queue, "priorities", "[Ljava/util/concurrent/ConcurrentLinkedQueue;", Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL);
        assertField(queue, "taskCount", "Ljava/util/concurrent/atomic/AtomicIntegerArray;", Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL);
        ClassNode executor = node("com/ishland/flowsched/executor/ExecutorManager");
        assertField(executor, "waitObj", "Ljava/util/concurrent/Semaphore;", Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);
        assertField(executor, "globalWorkQueue", "Lcom/ishland/flowsched/executor/BucketTaskPriorityQueue;", Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL);
        MethodNode reschedule = method(executor, "reschedule0", "(Lcom/ishland/flowsched/executor/Task;I)V");
        assertEquals(List.of("requeue", "release"), calls(reschedule), "reschedule0 enqueues, then releases one permit");
        for (String caller : List.of("schedule(Lcom/ishland/flowsched/executor/Task;I)V", "changePriority(Lcom/ishland/flowsched/executor/Task;I)V",
                "callListeners(Lcom/ishland/flowsched/executor/ExecutorManager$FreeableTaskList;)V")) {
            String name = caller.substring(0, caller.indexOf('('));
            assertTrue(calls(method(executor, name, caller.substring(name.length()))).contains("reschedule0"), caller);
        }
        assertEquals(List.of("acquireUninterruptibly", "pollTasks", "onSpinWait"),
                calls(method(node("com/ishland/flowsched/executor/WorkerThread"), "run", "()V")),
                "a worker takes a permit, then polls until it gets an entry");

        String configuration;
        try (var input = java.util.Objects.requireNonNull(getClass().getResourceAsStream("/phoenium.mixins.json"))) {
            configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(configuration.contains("\"FlowSchedStalePurgeMixin\""));
        var retained = Class.forName("dev.phoenium.mixin.PhoeniumMixinConfigPlugin").getDeclaredField("ENGINE_RETAINED");
        retained.setAccessible(true);
        assertTrue(((Set<?>) retained.get(null)).contains("dev.phoenium.mixin.FlowSchedStalePurgeMixin"));
        assertTrue(FlowSchedQueuePurge.ENABLED, "compaction is on by default");
    }

    /** The MEM-6 shape: tasks enqueued in the lowest bucket, re-prioritised and run; their old entries stay behind. */
    @Test void supersededEntriesStayReachableUntilCompacted() throws Exception {
        Sim sim = new Sim();
        for (int i = 0; i < 5000; i++) {
            Object task = newTask.newInstance((Runnable) () -> { });
            sim.enqueue(task, LOWEST);
            sim.enqueue(task, 10);
        }
        Object live = newTask.newInstance((Runnable) () -> { });
        sim.enqueue(live, LOWEST);
        for (int ran = 0; ran < 5000;) {
            Object task = sim.poll();
            assertNotNull(task);
            if (task != tombstone) { runTask.invoke(task, (Runnable) () -> { }); ran++; }
        }
        assertEquals(5001, sim.count.get(LOWEST), "every superseded entry is still queued (and keeps its task reachable)");
        assertEquals(5001, sim.entries());
        assertEquals(5001, sim.permits.availablePermits());

        assertEquals(1, sim.compact(LOWEST));
        assertEquals(1, sim.count.get(LOWEST));
        assertEquals(List.of(live), new ArrayList<>(sim.all[LOWEST]), "only the live task's entry is left");
        assertEquals(1, sim.permits.availablePermits(), "one permit retired per dropped entry");
        assertSame(live, sim.poll(), "the live task still runs");
        assertNull(sim.poll());
        assertEquals(0, sim.entries());
        for (int b = 0; b < BUCKETS; b++) assertEquals(0, sim.count.get(b), "bucket " + b);
    }

    /** A superseded entry whose permit a worker already holds stays queued for that worker. */
    @Test void entriesWhosePermitsAreHeldStayForTheirWorkers() throws Exception {
        Sim sim = new Sim();
        for (int i = 0; i < 10; i++) {
            Object task = newTask.newInstance((Runnable) () -> { });
            sim.enqueue(task, LOWEST);
            sim.enqueue(task, 10);
        }
        assertTrue(sim.permits.tryAcquire(20), "workers hold every permit");
        for (int i = 0; i < 10; i++) assertNotSame(tombstone, dequeue.invoke(sim.queue));   // they run the 10 live entries
        assertEquals(10, sim.compact(LOWEST), "no free permit: every superseded entry stays for a holding worker");
        sim.permits.release(4);                                                           // four workers gave theirs back
        assertEquals(6, sim.compact(LOWEST));
        assertEquals(0, sim.permits.availablePermits());
        for (int i = 0; i < 6; i++) assertSame(tombstone, dequeue.invoke(sim.queue), "the holders drain the rest");
        assertNull(dequeue.invoke(sim.queue));
    }

    /** Compaction keeps the bucket order and, within a bucket, the kept entries' order and identity. */
    @Test void compactionKeepsBucketOrderAndTaskIdentity() throws Exception {
        Sim sim = new Sim();
        List<Object> high = new ArrayList<>(), low = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Object moved = newTask.newInstance((Runnable) () -> { });
            sim.enqueue(moved, 5);
            sim.enqueue(moved, LOWEST);   // superseded entry in 5, live entry in LOWEST
            sim.enqueue(moved, 5);        // superseded in LOWEST, fresh entry in 5: bucket 5 holds two, both current
            high.add(moved);
            Object waiting = newTask.newInstance((Runnable) () -> { });
            sim.enqueue(waiting, LOWEST);
            low.add(waiting);
        }
        assertEquals(300, sim.compact(LOWEST));
        assertEquals(600, sim.compact(5), "both entries of a task currently in the bucket are kept; one yields TOMBSTONE later");
        assertEquals(sim.entries(), sim.permits.availablePermits());
        List<Object> order = new ArrayList<>();
        for (Object task; (task = sim.poll()) != null;) if (task != tombstone) order.add(task);
        assertEquals(600, order.size());
        for (int i = 0; i < 300; i++) {
            assertSame(high.get(i), order.get(i), "bucket 5 drains first, in its original order, as the same objects");
            assertSame(low.get(i), order.get(300 + i), "then the lowest bucket, in its original order");
        }
    }

    /**
     * Producers enqueue tasks in the lowest bucket and move them between buckets (including back to the lowest), workers
     * take permits and run what they poll, and a compactor compacts every bucket continuously: every task runs exactly
     * once, permits stay equal to entries, and everything ends empty.
     */
    @Test @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void concurrentCompactionNeverLosesOrDuplicatesTasks() throws Exception {
        for (int round = 0; round < 4; round++) {
            Sim sim = new Sim();
            int tasksPerProducer = 20_000, producers = 4, workers = 4, total = tasksPerProducer * producers;
            AtomicIntegerArray runs = new AtomicIntegerArray(total);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            List<Thread> producerThreads = new ArrayList<>(), workerThreads = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                int base = p * tasksPerProducer;
                producerThreads.add(start(failures, () -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < tasksPerProducer; i++) {
                        int id = base + i;
                        Object task = newTask.newInstance((Runnable) () -> runs.incrementAndGet(id));
                        sim.enqueue(task, LOWEST);
                        for (int moves = random.nextInt(4); moves > 0; moves--)
                            sim.enqueue(task, random.nextInt(5) == 0 ? LOWEST : random.nextInt(LOWEST));
                    }
                }));
            }
            AtomicBoolean done = new AtomicBoolean();
            for (int w = 0; w < workers; w++)
                workerThreads.add(start(failures, () -> {
                    while (!done.get()) {
                        Object task = sim.poll();
                        if (task != null && task != tombstone) runTask.invoke(task, (Runnable) () -> { });
                    }
                }));
            Thread compactor = start(failures, () -> {
                while (!done.get()) for (int b = 0; b < BUCKETS; b++) sim.compact(b);
            });
            for (Thread producer : producerThreads) producer.join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (sum(runs) < total && System.nanoTime() < deadline) Thread.sleep(5);
            done.set(true);
            for (Thread worker : workerThreads) worker.join();
            compactor.join();
            assertEquals(List.of(), failures);
            for (int i = 0; i < total; i++) assertEquals(1, runs.get(i), "task " + i + " ran " + runs.get(i) + " times");
            assertEquals(sim.entries(), sim.permits.availablePermits(), "permits equal entries");
            for (Object task; (task = sim.poll()) != null;) assertSame(tombstone, task, "a task was still runnable after its run");
            assertEquals(0, sim.entries());
            for (int b = 0; b < BUCKETS; b++) assertEquals(0, sim.count.get(b), "bucket " + b + " count");
        }
    }

    /**
     * The real executor: its worker threads, lock tokens over a small grid (tasks wait for locks and are reset and
     * re-enqueued by the lock listeners), producers enqueueing in the lowest bucket and re-prioritising (also back to the
     * lowest), and a compactor on the executor's own queue and permits. Every task runs exactly once and completes, and
     * once the work is done no permit is left over and every worker parks (none spins).
     */
    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void realExecutorWithLockWaitsRunsEveryTaskOnceAndWorkersParkAfterwards() throws Exception {
        Class<?> executorClass = loader.loadClass("com.ishland.flowsched.executor.ExecutorManager");
        Class<?> taskClass = loader.loadClass("com.ishland.flowsched.executor.Task");
        Class<?> tokenClass = loader.loadClass("com.ishland.flowsched.executor.LockToken");
        Class<?> usage = loader.loadClass("com.ishland.c2me.base.common.scheduler.LockTokenImpl$Usage");
        Constructor<?> newToken = loader.loadClass("com.ishland.c2me.base.common.scheduler.LockTokenImpl")
                .getConstructor(int.class, long.class, usage);
        Class<?> scheduled = loader.loadClass("com.ishland.c2me.base.common.scheduler.ScheduledTask");
        Constructor<?> newScheduled = scheduled.getConstructor(long.class, Supplier.class, tokenClass.arrayType());
        Method schedule = executorClass.getMethod("schedule", taskClass, int.class);
        Method change = executorClass.getMethod("changePriority", taskClass, int.class);
        Method future = scheduled.getMethod("getFuture");
        Object worldgen = usage.getField("WORLDGEN").get(null);
        Field queueField = executorClass.getDeclaredField("globalWorkQueue");
        Field workersField = executorClass.getDeclaredField("workerThreads");
        queueField.setAccessible(true);
        workersField.setAccessible(true);
        Object executor = executorClass.getConstructor(int.class).newInstance(4);
        try {
            Object queue = queueField.get(executor);
            Semaphore permits = (Semaphore) executorClass.getField("waitObj").get(executor);
            ConcurrentLinkedQueue<?>[] all = (ConcurrentLinkedQueue<?>[]) buckets.get(queue);
            AtomicIntegerArray count = counts(queue);
            ToIntFunction<Object> priority = priority();
            int producers = 3, perProducer = 15_000, total = producers * perProducer;
            AtomicIntegerArray runs = new AtomicIntegerArray(total);
            List<Object> futures = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            List<Thread> producerThreads = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                int base = p * perProducer;
                producerThreads.add(start(failures, () -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < perProducer; i++) {
                        int id = base + i;
                        Object tokens = Array.newInstance(tokenClass, 1 + random.nextInt(3));
                        for (int t = 0; t < Array.getLength(tokens); t++)
                            Array.set(tokens, t, newToken.newInstance(0, (long) random.nextInt(16), worldgen));
                        Supplier<CompletableFuture<Object>> action = () -> {
                            runs.incrementAndGet(id);
                            for (int spin = 0; spin < 50; spin++) Thread.onSpinWait();   // hold the locks briefly
                            return CompletableFuture.completedFuture(null);
                        };
                        Object task = newScheduled.newInstance((long) id, action, tokens);
                        futures.add(future.invoke(task));
                        schedule.invoke(executor, task, LOWEST);
                        for (int moves = random.nextInt(4); moves > 0; moves--)
                            change.invoke(executor, task, random.nextInt(5) == 0 ? LOWEST : random.nextInt(LOWEST));
                    }
                }));
            }
            AtomicBoolean done = new AtomicBoolean();
            Thread compactor = start(failures, () -> {
                while (!done.get())
                    for (int b = 0; b < all.length; b++) FlowSchedQueuePurge.compact(all[b], count, b, priority, permits);
            });
            for (Thread producer : producerThreads) producer.join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (sum(runs) < total && System.nanoTime() < deadline) Thread.sleep(5);
            done.set(true);
            compactor.join();
            assertEquals(List.of(), failures);
            for (int i = 0; i < total; i++) assertEquals(1, runs.get(i), "task " + i + " ran " + runs.get(i) + " times");
            for (Object f : futures) {
                CompletableFuture<?> completable = (CompletableFuture<?>) f;
                completable.get(10, TimeUnit.SECONDS);
                assertFalse(completable.isCompletedExceptionally());
            }
            Thread[] workers = (Thread[]) workersField.get(executor);
            long settle = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < settle && (permits.availablePermits() != 0 || Collections.frequency(
                    java.util.Arrays.stream(workers).map(Thread::getState).toList(), Thread.State.WAITING) != workers.length))
                Thread.sleep(10);
            assertEquals(0, permits.availablePermits(), "no permit left without an entry");
            for (Thread worker : workers) assertEquals(Thread.State.WAITING, worker.getState(), worker.getName() + " parks, not spins");
            for (int b = 0; b < all.length; b++) {
                assertEquals(0, count.get(b), "bucket " + b + " count");
                assertTrue(all[b].isEmpty(), "bucket " + b + " entries");
            }
        } finally {
            executorClass.getMethod("shutdown").invoke(executor);
        }
    }

    /** Through the hook path (afterEnqueue after every enqueue), the MEM-6 pattern stays bounded instead of growing. */
    @Test void hookKeepsTheLowestBucketBounded() throws Exception {
        Sim sim = new Sim();
        FlowSchedQueuePurge.State state = new FlowSchedQueuePurge.State();
        state.bind(sim.queue);
        int largest = 0;
        for (int i = 0; i < 200_000; i++) {
            Object task = newTask.newInstance((Runnable) () -> { });
            sim.enqueue(task, LOWEST);
            FlowSchedQueuePurge.afterEnqueue(state, null, sim.permits);
            sim.enqueue(task, 10);
            FlowSchedQueuePurge.afterEnqueue(state, null, sim.permits);
            Object polled = sim.poll();   // bucket 10 is never empty long enough to reach the lowest bucket
            if (polled != null && polled != tombstone) runTask.invoke(polled, (Runnable) () -> { });
            largest = Math.max(largest, sim.count.get(LOWEST));
        }
        assertTrue(largest <= FlowSchedQueuePurge.THRESHOLD + FlowSchedQueuePurge.CHECK_EVERY,
                "lowest bucket peaked at " + largest + " entries");
        assertEquals(sim.entries(), sim.permits.availablePermits(), "permits equal entries");
        assertTrue(FlowSchedQueuePurge.counters().contains("c2meQueuePurge=true"), FlowSchedQueuePurge.counters());
    }

    private interface Body { void run() throws Exception; }

    private static Thread start(List<Throwable> failures, Body body) {
        Thread thread = new Thread(() -> {
            try { body.run(); } catch (Throwable failure) { failures.add(failure); }
        });
        thread.start();
        return thread;
    }

    private static ToIntFunction<Object> priority() throws ReflectiveOperationException {
        return FlowSchedQueuePurge.priorityReader(loader);
    }

    private static AtomicIntegerArray counts(Object queue) throws IllegalAccessException {
        return (AtomicIntegerArray) counts.get(queue);
    }

    private static int sum(AtomicIntegerArray values) {
        int sum = 0;
        for (int i = 0; i < values.length(); i++) sum += values.get(i);
        return sum;
    }

    private static ClassNode node(String name) throws Exception {
        ClassNode node = new ClassNode();
        try (var input = loader.getResourceAsStream(name + ".class")) {
            new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + node.name + "." + name + descriptor));
    }

    private static List<String> calls(MethodNode method) {
        List<String> names = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions)
            if (insn instanceof MethodInsnNode call) names.add(call.name);
        return names;
    }

    private static void assertField(ClassNode node, String name, String descriptor, int access) {
        FieldNode field = node.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
        assertEquals(descriptor, field.desc);
        assertEquals(access, field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL), name);
    }
}
