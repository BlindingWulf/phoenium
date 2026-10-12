package dev.phoenium.chunk;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.ToIntFunction;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S9-M6b (MEM-6, D40): FlowSchedQueuePurge against the real FlowSched queue and task classes of the pinned C2ME build
 * (loaded from its nested base jar; class hashes pinned in C2meScheduledTaskReleaseBinaryTest).
 */
final class FlowSchedQueuePurgeTest {
    private static final Path C2ME = Path.of("build", "stage6-audit-20260909", "research", "artifacts", "c2me",
            "c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar");
    private static final String BASE = "META-INF/jars/c2me-fabric-base-mc26.2-0.4.2-alpha.0.43-all.jar";
    private static final int BUCKETS = 46, LOWEST = 45;

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
        Class<?> simple = loader.loadClass("com.ishland.flowsched.executor.SimpleTask");
        newQueue = queue.getConstructor(int.class);
        newTask = simple.getConstructor(Runnable.class);
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

    @Test void queueShapeMatchesTheHook() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(loader.getResourceAsStream("com/ishland/flowsched/executor/BucketTaskPriorityQueue.class"))
                .accept(node, ClassReader.SKIP_FRAMES);
        assertField(node, "priorities", "[Ljava/util/concurrent/ConcurrentLinkedQueue;");
        assertField(node, "taskCount", "Ljava/util/concurrent/atomic/AtomicIntegerArray;");
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("enqueue0") && m.desc.equals("(Lcom/ishland/flowsched/executor/Task;I)V")));
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
        Object queue = newQueue.newInstance(BUCKETS);
        List<Object> tasks = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            Object task = newTask.newInstance((Runnable) () -> { });
            requeue.invoke(queue, task, LOWEST);
            requeue.invoke(queue, task, 10);
            tasks.add(task);
        }
        Object live = newTask.newInstance((Runnable) () -> { });
        requeue.invoke(queue, live, LOWEST);
        int ran = drainUntilLowest(queue, 5000);
        assertEquals(5000, ran);
        AtomicIntegerArray count = counts(queue);
        assertEquals(5001, count.get(LOWEST), "every superseded entry is still queued (and keeps its task reachable)");
        assertEquals(5001, bucket(queue, LOWEST).size());

        int kept = FlowSchedQueuePurge.compact(bucket(queue, LOWEST), count, LOWEST, priority());
        assertEquals(1, kept);
        assertEquals(1, count.get(LOWEST));
        assertEquals(List.of(live), new ArrayList<>(bucket(queue, LOWEST)), "only the live task's entry is left");
        assertSame(live, dequeue.invoke(queue), "the live task still runs");
        assertNull(dequeue.invoke(queue));
        for (int b = 0; b < BUCKETS; b++) assertEquals(0, count.get(b), "bucket " + b);
    }

    /**
     * Producers enqueue tasks in the lowest bucket and move them between buckets (including back to the lowest one),
     * workers dequeue and run them, and a compactor compacts every bucket continuously: every task runs exactly once,
     * no entry or count is lost, and the queue ends empty.
     */
    @Test @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void concurrentCompactionNeverLosesOrDuplicatesTasks() throws Exception {
        for (int round = 0; round < 4; round++) {
            Object queue = newQueue.newInstance(BUCKETS);
            int tasksPerProducer = 20_000, producers = 4, workers = 4, total = tasksPerProducer * producers;
            AtomicIntegerArray runs = new AtomicIntegerArray(total);
            AtomicIntegerArray count = counts(queue);
            ConcurrentLinkedQueue<?>[] all = (ConcurrentLinkedQueue<?>[]) buckets.get(queue);
            ToIntFunction<Object> priority = priority();
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<Thread> producerThreads = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                int base = p * tasksPerProducer;
                producerThreads.add(start(failures, () -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < tasksPerProducer; i++) {
                        int id = base + i;
                        Object task = newTask.newInstance((Runnable) () -> runs.incrementAndGet(id));
                        requeue.invoke(queue, task, LOWEST);
                        for (int moves = random.nextInt(4); moves > 0; moves--)
                            requeue.invoke(queue, task, random.nextInt(5) == 0 ? LOWEST : random.nextInt(LOWEST));
                    }
                }));
            }
            AtomicBoolean done = new AtomicBoolean();
            for (int w = 0; w < workers; w++)
                threads.add(start(failures, () -> {
                    while (!done.get()) {
                        Object task = dequeue.invoke(queue);
                        if (task != null && task != tombstone) runTask.invoke(task, (Runnable) () -> { });
                    }
                }));
            Thread compactor = start(failures, () -> {
                while (!done.get())
                    for (int b = 0; b < BUCKETS; b++) FlowSchedQueuePurge.compact(all[b], count, b, priority);
            });
            for (Thread producer : producerThreads) producer.join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (sum(runs) < total && System.nanoTime() < deadline) Thread.sleep(5);
            done.set(true);
            for (Thread thread : threads) thread.join();
            compactor.join();
            assertEquals(List.of(), failures);
            for (Object task; (task = dequeue.invoke(queue)) != null;)
                if (task != tombstone) fail("a task was still runnable after its run");
            for (int i = 0; i < total; i++) assertEquals(1, runs.get(i), "task " + i + " ran " + runs.get(i) + " times");
            for (int b = 0; b < BUCKETS; b++) {
                assertEquals(0, count.get(b), "bucket " + b + " count");
                assertTrue(all[b].isEmpty(), "bucket " + b + " entries");
            }
        }
    }

    private interface Body { void run() throws Exception; }

    private static Thread start(List<Throwable> failures, Body body) {
        Thread thread = new Thread(() -> {
            try { body.run(); } catch (Throwable failure) { failures.add(failure); }
        });
        thread.start();
        return thread;
    }

    private static int drainUntilLowest(Object queue, int expected) throws Exception {
        int ran = 0;
        while (ran < expected) {
            Object task = dequeue.invoke(queue);
            assertNotNull(task);
            if (task == tombstone) continue;
            runTask.invoke(task, (Runnable) () -> { });
            ran++;
        }
        return ran;
    }

    private static ToIntFunction<Object> priority() throws ReflectiveOperationException {
        return FlowSchedQueuePurge.priorityReader(loader);
    }

    private static AtomicIntegerArray counts(Object queue) throws IllegalAccessException {
        return (AtomicIntegerArray) counts.get(queue);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<Object> bucket(Object queue, int bucket) throws IllegalAccessException {
        return (ConcurrentLinkedQueue<Object>) ((ConcurrentLinkedQueue<?>[]) buckets.get(queue))[bucket];
    }

    private static int sum(AtomicIntegerArray values) {
        int sum = 0;
        for (int i = 0; i < values.length(); i++) sum += values.get(i);
        return sum;
    }

    private static void assertField(ClassNode node, String name, String descriptor) {
        FieldNode field = node.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
        assertEquals(descriptor, field.desc);
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL));
    }
}
