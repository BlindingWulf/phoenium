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
import org.junit.jupiter.api.RepeatedTest;
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

    /**
     * A bucket queue whose remove/add fail on demand, only on the target (compactor) thread and, if {@code failRestorer},
     * for adds of the restorer thread: FlowSched's own calls never fail.
     */
    static final class FaultyQueue extends ConcurrentLinkedQueue<Object> {
        volatile Thread target;
        volatile boolean failRestorer;
        volatile java.util.function.IntPredicate failRemove = n -> false, failAdd = n -> false;
        final java.util.concurrent.atomic.AtomicInteger removeCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger addCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger injected = new java.util.concurrent.atomic.AtomicInteger();
        volatile Object lastRemoved;

        @Override public boolean remove(Object o) {
            boolean mine = Thread.currentThread() == target;
            if (mine && failRemove.test(removeCalls.incrementAndGet())) {
                injected.incrementAndGet();
                throw new IllegalStateException("injected remove failure");
            }
            boolean removed = super.remove(o);
            if (removed && mine) lastRemoved = o;
            return removed;
        }

        @Override public boolean add(Object o) {
            Thread current = Thread.currentThread();
            boolean mine = current == target || failRestorer && current == FlowSchedQueuePurge.RESTORER.thread;
            if (mine && failAdd.test(addCalls.incrementAndGet())) {
                injected.incrementAndGet();
                throw new OutOfMemoryError("injected allocation failure");
            }
            return super.add(o);
        }
    }

    /** The executor's permits; tryAcquire fails on demand, only on the target (compactor) thread. */
    static final class FaultySemaphore extends Semaphore {
        volatile Thread target;
        volatile java.util.function.IntPredicate failTry = n -> false;
        final java.util.concurrent.atomic.AtomicInteger tryCalls = new java.util.concurrent.atomic.AtomicInteger();

        FaultySemaphore() { super(0); }

        @Override public boolean tryAcquire() {
            if (Thread.currentThread() == target && failTry.test(tryCalls.incrementAndGet()))
                throw new IllegalStateException("injected permit failure");
            return super.tryAcquire();
        }
    }

    /** A BucketTaskPriorityQueue with the executor's permit accounting (ExecutorManager.reschedule0 / WorkerThread). */
    private static final class Sim {
        final Object queue;
        final FaultySemaphore permits = new FaultySemaphore();
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

        /** Replaces bucket {@code bucket} of the real queue with a FaultyQueue holding the same entries in the same order. */
        FaultyQueue faulty(int bucket) {
            FaultyQueue replacement = new FaultyQueue();
            replacement.addAll(all[bucket]);
            all[bucket] = replacement;
            return replacement;
        }

        /**
         * FlowSched's invariants: every bucket's count equals its entries, permits equal entries, and every task whose
         * priority is a bucket has an entry there (else it would never run).
         */
        void assertConsistent(List<Object> tasks, ToIntFunction<Object> priority, String context) {
            for (int b = 0; b < BUCKETS; b++) assertEquals(all[b].size(), count.get(b), context + ": bucket " + b + " count");
            assertEquals(entries(), permits.availablePermits(), context + ": permits equal entries");
            for (Object task : tasks) {
                int p = priority.applyAsInt(task);
                if (p >= 0) assertTrue(all[p].stream().anyMatch(entry -> entry == task), context + ": " + task + " lost its entry in " + p);
            }
        }

        /** As the workers would: poll and run everything; every task must run exactly once; ends empty. */
        void assertDrainsRunningEachOnce(List<Object> tasks, String context) throws Exception {
            java.util.IdentityHashMap<Object, Integer> runs = new java.util.IdentityHashMap<>();
            for (Object entry; (entry = poll()) != null;) {
                if (entry == tombstone) continue;
                runTask.invoke(entry, (Runnable) () -> { });
                runs.merge(entry, 1, Integer::sum);
            }
            for (Object task : tasks) assertEquals(1, runs.getOrDefault(task, 0), context + ": runs of " + task);
            assertEquals(tasks.size(), runs.size(), context + ": nothing else ran");
            assertEquals(0, entries(), context + ": drained");
            assertEquals(0, permits.availablePermits(), context + ": no permit left");
            for (int b = 0; b < BUCKETS; b++) assertEquals(0, count.get(b), context + ": bucket " + b + " count");
        }
    }

    private static long counter(String name) {
        var matcher = java.util.regex.Pattern.compile(name + "=(\\d+)").matcher(FlowSchedQueuePurge.counters());
        assertTrue(matcher.find(), name);
        return Long.parseLong(matcher.group(1));
    }

    /** Waits until the restorer has put back every handed-off entry (they wait for memory, not for a later compaction). */
    private static void awaitRestores(String context) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (FlowSchedQueuePurge.RESTORER.pending() > 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(0, FlowSchedQueuePurge.RESTORER.pending(), context + ": every handed-off entry is back");
    }

    /** 150 tasks: live ones in the lowest bucket, superseded ones moved to 10, some moved back (two entries there). */
    private static List<Object> mixedLowestBucket(Sim sim) throws Exception {
        List<Object> tasks = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            Object task = newTask.newInstance((Runnable) () -> { });
            sim.enqueue(task, LOWEST);
            if (i % 3 != 0) sim.enqueue(task, 10);
            if (i % 5 == 1) sim.enqueue(task, LOWEST);   // back again: a superseded and a live entry of the same task
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * A failure at every step of a compaction, at several points of the scan, loses no task, duplicates none, blocks none
     * and leaves FlowSched's accounting intact: scan/filter (priority read), permit acquisition, the removal itself, the
     * re-check after a removal, and the restore (its allocation fails: the entry goes to the restorer, whose first attempts
     * fail too, and the compaction stops). After each, the queue drains and every task runs exactly once.
     */
    @Test void aFailureAtAnyStepOfACompactionLosesNothing() throws Exception {
        for (String stage : List.of("filter", "permit", "remove", "recheck", "restore")) {
            for (int k : new int[] {1, 2, 7, 40, 75}) {   // 80 superseded entries: every stage reaches each k
                String context = stage + "@" + k;
                Sim sim = new Sim();
                List<Object> tasks = mixedLowestBucket(sim);
                FaultyQueue lowest = sim.faulty(LOWEST);
                ToIntFunction<Object> real = priority();
                int[] reads = {0}, rechecks = {0};
                ToIntFunction<Object> reader = task -> {
                    boolean recheck = task == lowest.lastRemoved;
                    if (recheck) lowest.lastRemoved = null;
                    if (stage.equals("filter") && !recheck && ++reads[0] == k) throw new IllegalStateException("injected read failure");
                    if (recheck && ++rechecks[0] == k) {
                        if (stage.equals("recheck")) throw new IllegalStateException("injected re-check failure");
                        if (stage.equals("restore")) return LOWEST;   // as if the task moved back: forces a restore
                    }
                    return real.applyAsInt(task);
                };
                lowest.target = Thread.currentThread();
                sim.permits.target = Thread.currentThread();
                if (stage.equals("permit")) sim.permits.failTry = n -> n == k;
                if (stage.equals("remove")) lowest.failRemove = n -> n == k;
                if (stage.equals("restore")) {   // the restore's allocation fails, then the restorer's first two
                    lowest.failRestorer = true;
                    lowest.failAdd = n -> n <= 3;
                }
                long retriesBefore = counter("c2meQueueRestoreRetries"), handedOffBefore = counter("c2meQueueRestoreHandedOff");
                long lateBefore = counter("c2meQueueRestoredLate");
                Throwable thrown = null;
                try {
                    FlowSchedQueuePurge.compact(lowest, sim.count, LOWEST, reader, sim.permits);
                } catch (IllegalStateException expected) {
                    thrown = expected;
                }
                lowest.target = null;
                sim.permits.target = null;
                int reached = switch (stage) {
                    case "filter" -> reads[0];
                    case "permit" -> sim.permits.tryCalls.get();
                    case "remove" -> lowest.removeCalls.get();
                    default -> rechecks[0];
                };
                assertTrue(reached >= k, context + ": the scan reached step " + k + " (" + reached + ")");
                if (stage.equals("restore")) {
                    assertNull(thrown, context + ": a restore that cannot allocate does not fail the compaction");
                    assertEquals(k, reached, context + ": the compaction stopped at the handoff");
                    awaitRestores(context);
                    assertEquals(1, counter("c2meQueueRestoreHandedOff") - handedOffBefore, context + ": handed off once");
                    assertEquals(2, counter("c2meQueueRestoreRetries") - retriesBefore, context + ": the restorer retried");
                    assertEquals(1, counter("c2meQueueRestoredLate") - lateBefore, context + ": the restorer put it back");
                    lowest.failRestorer = false;
                } else {
                    assertNotNull(thrown, context + ": the injected failure surfaced");
                }
                sim.assertConsistent(tasks, real, context);
                FlowSchedQueuePurge.compact(lowest, sim.count, LOWEST, real, sim.permits);   // a later compaction completes
                sim.assertConsistent(tasks, real, context + " then complete");
                sim.assertDrainsRunningEachOnce(tasks, context);
            }
        }
    }

    /**
     * A restore whose allocation fails on an interrupted compacting thread returns at once (it never waits for memory) and
     * keeps the interrupt. The restorer, itself interrupted while memory stays short, waits between attempts (no busy
     * spin) and never gives up; meanwhile the entry and its permit stay out together. Once memory is back, entry, count and
     * permit return and the task runs once.
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aRestoreThatCannotAllocateReturnsAtOnceAndTheRestorerPutsItBack() throws Exception {
        Sim sim = new Sim();
        Object task = newTask.newInstance((Runnable) () -> { });
        sim.enqueue(task, LOWEST);
        FaultyQueue lowest = sim.faulty(LOWEST);
        assertTrue(sim.permits.tryAcquire());                  // removed as a compaction would: entry and permit
        assertTrue(lowest.remove(task));
        sim.count.decrementAndGet(LOWEST);
        FlowSchedQueuePurge.Restorer restorer = FlowSchedQueuePurge.RESTORER;
        restorer.start();
        AtomicBoolean shortOfMemory = new AtomicBoolean(true);
        lowest.target = Thread.currentThread();
        lowest.failRestorer = true;
        lowest.failAdd = n -> shortOfMemory.get();
        long handedOff = restorer.handedOff.get(), retries = restorer.retries.get();
        try {
            Thread.currentThread().interrupt();
            long start = System.nanoTime();
            boolean back;
            try {
                back = FlowSchedQueuePurge.restore(lowest, sim.count, LOWEST, task, sim.permits, restorer);
            } finally {
                lowest.target = null;
                assertTrue(Thread.interrupted(), "the interrupt status is kept (and cleared here)");
            }
            assertFalse(back, "not back yet: memory is short");
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500), "returned without waiting for memory");
            assertEquals(1, restorer.handedOff.get() - handedOff);
            restorer.thread.interrupt();                       // must neither stop the restorer nor make it spin
            while (restorer.retries.get() - retries < 10) Thread.sleep(5);
            long settled = restorer.retries.get();
            Thread.sleep(300);
            assertTrue(restorer.retries.get() - settled < 40, "the restorer backs off (" + (restorer.retries.get() - settled)
                    + " attempts in 300 ms)");
            assertTrue(restorer.thread.isAlive());
            assertEquals(1, restorer.pending());
            assertEquals(0, lowest.size(), "the entry is out while memory is short");
            assertEquals(0, sim.count.get(LOWEST));
            assertEquals(0, sim.permits.availablePermits(), "its permit stays retired with it: no worker spins for it");
        } finally {
            shortOfMemory.set(false);
            lowest.failRestorer = false;
        }
        awaitRestores("memory back");
        sim.assertConsistent(List.of(task), priority(), "restored");
        sim.assertDrainsRunningEachOnce(List.of(task), "restored");
    }

    /**
     * Sustained memory pressure (owner 2026-10-12): every allocation of the compactor and of the restorer fails for over a
     * second, starting inside the move-back race, played deterministically so that the removal takes the task's only live
     * entry (its old entry was polled as a TOMBSTONE while it was in bucket 10, then it moved back). The compacting thread
     * is interrupted and holds a monitor that every task run also takes (as C2ME's SchedulingManager holds a position's
     * task list around changePriority while finishing tasks take it): the compaction returns at once, keeps the interrupt
     * and the order and identity of the entries it kept, and no further compaction is admitted while the entry is out.
     * Under the same pressure workers run every other task, including tasks re-prioritised concurrently under the monitor,
     * without waiting long for it; the task waits (it is neither lost nor run twice), or, moved to another bucket meanwhile,
     * runs from there. Once memory is back its entry returns behind the entries enqueued meanwhile, and everything runs
     * exactly once with counts and permits matching the entries throughout.
     */
    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void sustainedMemoryPressureLosesNoTaskAndBlocksNoProgress() throws Exception {
        for (boolean movedAway : new boolean[] {false, true}) {
            String context = movedAway ? "moved away under pressure" : "waits for memory";
            Sim sim = new Sim();
            FaultyQueue lowest = sim.faulty(LOWEST);
            int keptTasks = 100, others = 200, moving = 100, late = 10;
            int total = 1 + keptTasks + others + moving + late;
            AtomicIntegerArray runs = new AtomicIntegerArray(total);
            List<Object> tasks = new ArrayList<>();
            for (int id = 0; id < total; id++) {
                int which = id;
                tasks.add(newTask.newInstance((Runnable) () -> runs.incrementAndGet(which)));
            }
            Object task = tasks.get(0);
            List<Object> kept = tasks.subList(1, 1 + keptTasks);
            sim.enqueue(task, LOWEST);                         // its first entry, at the head of the lowest bucket
            sim.enqueue(task, 10);                             // moved: that entry is superseded
            for (Object live : kept) sim.enqueue(live, LOWEST);
            for (Object other : tasks.subList(1 + keptTasks, 1 + keptTasks + others)) sim.enqueue(other, 10);

            AtomicBoolean shortOfMemory = new AtomicBoolean(), raced = new AtomicBoolean();
            lowest.failRestorer = true;
            lowest.failAdd = n -> shortOfMemory.get();
            ToIntFunction<Object> real = priority();
            ToIntFunction<Object> reader = entry -> {
                int value = real.applyAsInt(entry);
                if (entry == task && raced.compareAndSet(false, true)) {
                    // Between this read and the removal: a worker polls the old entry (TOMBSTONE: the task is in 10) ...
                    assertSame(task, lowest.poll());
                    sim.count.decrementAndGet(LOWEST);
                    assertTrue(sim.permits.tryAcquire());
                    try {
                        sim.enqueue(task, LOWEST);             // ... and the task moves back: its only live entry, at the tail
                    } catch (Exception impossible) {
                        throw new AssertionError(impossible);
                    }
                    shortOfMemory.set(true);                   // from here on the compactor and the restorer cannot allocate
                }
                return value;
            };
            Object monitor = new Object();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicLong compactNanos = new java.util.concurrent.atomic.AtomicLong(-1);
            AtomicBoolean interruptKept = new AtomicBoolean();
            long handedOff = FlowSchedQueuePurge.RESTORER.handedOff.get(), retries = FlowSchedQueuePurge.RESTORER.retries.get();
            List<Thread> threads = new ArrayList<>();
            AtomicBoolean done = new AtomicBoolean();
            try {
                Thread compactor = start(failures, () -> {
                    lowest.target = Thread.currentThread();
                    Thread.currentThread().interrupt();
                    long begin = System.nanoTime();
                    synchronized (monitor) {
                        assertEquals(keptTasks, FlowSchedQueuePurge.compact(lowest, sim.count, LOWEST, reader, sim.permits));
                    }
                    compactNanos.set(System.nanoTime() - begin);
                    interruptKept.set(Thread.interrupted());
                });
                compactor.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(compactor.isAlive(), context + ": the compaction returned although memory stayed short");
                assertEquals(List.of(), failures);
                long pressureStart = System.nanoTime();
                assertTrue(raced.get() && shortOfMemory.get(), context + ": the race was played");
                assertTrue(interruptKept.get(), context + ": the compacting thread keeps its interrupt");
                assertTrue(compactNanos.get() < TimeUnit.MILLISECONDS.toNanos(500), context + ": took " + compactNanos.get() + " ns");
                assertEquals(1, FlowSchedQueuePurge.RESTORER.handedOff.get() - handedOff, context + ": handed off");
                assertEquals(1, FlowSchedQueuePurge.RESTORER.pending(), context);
                assertEquals(kept, new ArrayList<>(lowest), context + ": kept entries keep their order and identity");
                assertFalse(lowest.stream().anyMatch(entry -> entry == task), context + ": its entry is out");
                assertEquals(LOWEST, real.applyAsInt(task), context + ": the task still belongs to the lowest bucket");
                assertAccounting(sim, context + ": entry out");
                assertEquals(-1, FlowSchedQueuePurge.compact(lowest, sim.count, LOWEST, real, sim.permits),
                        context + ": no compaction is admitted while the entry is out");
                assertEquals(keptTasks, lowest.size());

                // Under the same pressure: workers (taking the monitor for every task, as C2ME's postExec does) and a
                // re-prioritiser (moving tasks under the monitor, whose compaction attempts are not admitted).
                java.util.concurrent.atomic.AtomicLong maxMonitorWait = new java.util.concurrent.atomic.AtomicLong();
                java.util.concurrent.atomic.AtomicInteger admitted = new java.util.concurrent.atomic.AtomicInteger();
                for (int w = 0; w < 2; w++)
                    threads.add(start(failures, () -> {
                        while (!done.get()) {
                            Object entry = sim.poll();
                            if (entry == null) {
                                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                                continue;
                            }
                            if (entry == tombstone) continue;
                            long waitStart = System.nanoTime();
                            synchronized (monitor) {
                                maxMonitorWait.accumulateAndGet(System.nanoTime() - waitStart, Math::max);
                            }
                            runTask.invoke(entry, (Runnable) () -> { });
                        }
                    }));
                List<Object> movingTasks = tasks.subList(1 + keptTasks + others, 1 + keptTasks + others + moving);
                threads.add(start(failures, () -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (Object mover : movingTasks) {
                        synchronized (monitor) {
                            sim.enqueue(mover, LOWEST);
                            if (FlowSchedQueuePurge.compact(lowest, sim.count, LOWEST, real, sim.permits) >= 0) admitted.incrementAndGet();
                        }
                        for (int moves = 1 + random.nextInt(3); moves > 0; moves--)
                            synchronized (monitor) {
                                sim.enqueue(mover, random.nextInt(4) == 0 ? LOWEST : random.nextInt(LOWEST));
                            }
                    }
                }));
                if (movedAway) sim.enqueue(task, 5);           // the task itself can still move, and run from there
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                int expected = total - late - (movedAway ? 0 : 1);
                while ((sum(runs) < expected || sim.entries() > 0 || System.nanoTime() - pressureStart < TimeUnit.SECONDS.toNanos(1)
                        || FlowSchedQueuePurge.RESTORER.retries.get() - retries < 20) && System.nanoTime() < deadline)
                    Thread.sleep(5);
                assertEquals(expected, sum(runs), context + ": every other task ran while memory stayed short");
                assertTrue(FlowSchedQueuePurge.RESTORER.retries.get() - retries >= 20
                        && System.nanoTime() - pressureStart >= TimeUnit.SECONDS.toNanos(1), context + ": the pressure was sustained");
                assertEquals(movedAway ? 1 : 0, runs.get(0), context + ": the task ran from its new bucket, or waits");
                assertEquals(0, sim.entries(), context + ": the workers drained everything else");
                assertEquals(0, admitted.get(), context + ": no compaction was admitted under pressure");
                assertTrue(maxMonitorWait.get() < TimeUnit.MILLISECONDS.toNanos(250),
                        context + ": longest wait for the monitor " + maxMonitorWait.get() + " ns");
                assertTrue(shortOfMemory.get() && FlowSchedQueuePurge.RESTORER.pending() == 1, context + ": still short of memory");
                done.set(true);
                for (Thread thread : threads) thread.join();
                assertEquals(List.of(), failures);
                assertAccounting(sim, context + ": after the workers stopped");

                List<Object> lateTasks = tasks.subList(total - late, total);
                for (Object lateTask : lateTasks) sim.enqueue(lateTask, LOWEST);   // enqueued while the entry is out
                long released = System.nanoTime();
                shortOfMemory.set(false);
                awaitRestores(context);
                assertTrue(System.nanoTime() - released < TimeUnit.SECONDS.toNanos(1), context + ": back promptly");
                List<Object> expectedOrder = new ArrayList<>(lateTasks);
                expectedOrder.add(task);
                assertEquals(expectedOrder, new ArrayList<>(lowest), context + ": the entry is back, at the tail, as itself");
                assertAccounting(sim, context + ": entry back");
                List<Object> order = new ArrayList<>();
                for (Object entry; (entry = sim.poll()) != null;) {
                    if (entry == tombstone) continue;
                    runTask.invoke(entry, (Runnable) () -> { });
                    order.add(entry);
                }
                assertEquals(movedAway ? lateTasks : expectedOrder, order, context + ": run in queue order");
                for (int id = 0; id < total; id++) assertEquals(1, runs.get(id), context + ": runs of task " + id);
                assertEquals(0, sim.entries());
                assertEquals(0, sim.permits.availablePermits(), context + ": no permit left");
                for (int b = 0; b < BUCKETS; b++) assertEquals(0, sim.count.get(b), context + ": bucket " + b);
            } finally {
                done.set(true);
                shortOfMemory.set(false);
                lowest.failRestorer = false;
                lowest.target = null;
                for (Thread thread : threads) thread.join();
            }
        }
    }

    /** Counts equal each bucket's entries and permits equal all entries (FlowSched's accounting). */
    private static void assertAccounting(Sim sim, String context) {
        for (int b = 0; b < BUCKETS; b++) assertEquals(sim.all[b].size(), sim.count.get(b), context + ": bucket " + b + " count");
        assertEquals(sim.entries(), sim.permits.availablePermits(), context + ": permits equal entries");
    }

    /**
     * The concurrent workload of concurrentCompactionNeverLosesOrDuplicatesTasks, with failures injected into the
     * compactor at random steps (reads, permits, removals, restores' allocations): every task still runs exactly once and
     * the accounting holds.
     */
    @Test @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void concurrentCompactionWithInjectedFailuresNeverLosesOrDuplicatesTasks() throws Exception {
        for (int round = 0; round < 3; round++) {
            Sim sim = new Sim();
            FaultyQueue lowest = sim.faulty(LOWEST), ten = sim.faulty(10);
            int tasksPerProducer = 15_000, producers = 4, workers = 4, total = tasksPerProducer * producers;
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
                            sim.enqueue(task, random.nextInt(4) == 0 ? LOWEST : random.nextInt(3) == 0 ? 10 : random.nextInt(LOWEST));
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
            ToIntFunction<Object> real = priority();
            java.util.concurrent.atomic.AtomicInteger injected = new java.util.concurrent.atomic.AtomicInteger();
            Thread compactor = start(failures, () -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                lowest.target = ten.target = sim.permits.target = Thread.currentThread();
                lowest.failRestorer = ten.failRestorer = true;   // the restorer's attempts fail as often
                lowest.failRemove = ten.failRemove = n -> random.nextInt(50) == 0;
                lowest.failAdd = ten.failAdd = n -> ThreadLocalRandom.current().nextInt(3) == 0;
                sim.permits.failTry = n -> random.nextInt(100) == 0;
                ToIntFunction<Object> reader = task -> {
                    if (random.nextInt(200) == 0) { injected.incrementAndGet(); throw new IllegalStateException("injected read failure"); }
                    return real.applyAsInt(task);
                };
                while (!done.get()) {
                    for (int b = 0; b < BUCKETS; b++) {
                        try {
                            FlowSchedQueuePurge.compact((ConcurrentLinkedQueue<Object>) sim.all[b], sim.count, b, reader, sim.permits);
                        } catch (IllegalStateException expected) {
                            injected.incrementAndGet();
                        }
                    }
                }
                lowest.target = ten.target = sim.permits.target = null;
            });
            long handedOff = counter("c2meQueueRestoreHandedOff");
            for (Thread producer : producerThreads) producer.join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (sum(runs) < total && System.nanoTime() < deadline) Thread.sleep(5);
            done.set(true);
            for (Thread worker : workerThreads) worker.join();
            compactor.join();
            assertEquals(List.of(), failures);
            awaitRestores("round " + round);
            lowest.failRestorer = ten.failRestorer = false;
            assertTrue(injected.get() + lowest.injected.get() + ten.injected.get() > 0, "failures were injected");
            assertTrue(counter("c2meQueueRestoreHandedOff") > handedOff, "restores were handed off");
            for (int i = 0; i < total; i++) assertEquals(1, runs.get(i), "task " + i + " ran " + runs.get(i) + " times");
            assertEquals(sim.entries(), sim.permits.availablePermits(), "permits equal entries");
            for (Object task; (task = sim.poll()) != null;) assertSame(tombstone, task, "a task was still runnable after its run");
            assertEquals(0, sim.entries());
            for (int b = 0; b < BUCKETS; b++) assertEquals(0, sim.count.get(b), "bucket " + b + " count");
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

    /**
     * A compaction that fails midway (here a throwing priority read, standing in for any error, e.g. an OutOfMemoryError
     * while the kept list grows under exactly the memory pressure MEM-6 creates) loses no entry: everything taken out
     * and not dropped with its permit goes back, counts match the entries and permits equal entries.
     */
    @Test void compactionThatFailsMidwayLosesNoEntry() throws Exception {
        Sim sim = new Sim();
        List<Object> live = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            Object task = newTask.newInstance((Runnable) () -> { });
            sim.enqueue(task, LOWEST);
            if (i % 3 == 0) live.add(task);
            else sim.enqueue(task, 10);                          // 50 live entries in the lowest bucket, 100 superseded
        }
        int queued = sim.count.get(LOWEST), permitsBefore = sim.permits.availablePermits();
        ToIntFunction<Object> real = priority();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ToIntFunction<Object> failing = task -> {
            if (reads.incrementAndGet() == 75) throw new IllegalStateException("injected failure");
            return real.applyAsInt(task);
        };
        assertThrows(IllegalStateException.class,
                () -> FlowSchedQueuePurge.compact(sim.all[LOWEST], sim.count, LOWEST, failing, sim.permits));
        int dropped = permitsBefore - sim.permits.availablePermits();
        assertTrue(dropped > 0 && dropped < 100, "dropped some superseded entries before failing: " + dropped);
        assertEquals(queued - dropped, sim.all[LOWEST].size(), "every entry taken out and not dropped went back");
        assertEquals(sim.all[LOWEST].size(), sim.count.get(LOWEST), "the count matches the entries");
        assertEquals(sim.entries(), sim.permits.availablePermits(), "permits equal entries");
        for (Object task : live)
            assertTrue(sim.all[LOWEST].stream().anyMatch(entry -> entry == task), "live task " + task + " is still queued");
        assertEquals(50, sim.compact(LOWEST), "a later compaction finishes the job");
        assertEquals(50, sim.count.get(LOWEST));
        assertEquals(sim.entries(), sim.permits.availablePermits());
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
    @RepeatedTest(25) @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void realExecutorWithLockWaitsRunsEveryTaskOnceAndWorkersParkAfterwards() throws Exception {
        long dropped = realExecutor(true, false);
        assertTrue(dropped > 0, "the compactor dropped superseded entries (" + dropped + ")");
    }

    /** The same workload without the compactor: the control arm for the test itself (FlowSched as shipped). */
    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void realExecutorControlWithoutCompaction() throws Exception {
        assertEquals(0, realExecutor(false, false));
    }

    /**
     * The real-executor workload under sustained memory pressure: a third of the compactor's removals in the lowest bucket
     * must be restored (forced re-check, as if the task had moved back) and every allocation of the compactor and of the
     * restorer fails from the start until 1.5 s after the producers finish. The compactor works under a monitor that every
     * task also takes when it runs (C2ME's position task list): no task waits long for it, and once memory is back every
     * task runs once and completes, the queue ends empty, no permit is left and every worker parks.
     */
    @RepeatedTest(5) @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void realExecutorUnderSustainedMemoryPressure() throws Exception {
        realExecutor(true, true);
    }

    private static long droppedSoFar() {
        var matcher = java.util.regex.Pattern.compile("c2meQueueDropped=(\\d+)").matcher(FlowSchedQueuePurge.counters());
        assertTrue(matcher.find());
        return Long.parseLong(matcher.group(1));
    }

    /**
     * Runs the real-executor workload, with or without a concurrent compactor, optionally under sustained memory pressure
     * (see realExecutorUnderSustainedMemoryPressure); returns the entries it dropped.
     */
    private long realExecutor(boolean compact, boolean pressure) throws Exception {
        long droppedBefore = droppedSoFar();
        long handedOffBefore = FlowSchedQueuePurge.RESTORER.handedOff.get(), retriesBefore = FlowSchedQueuePurge.RESTORER.retries.get();
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
            ToIntFunction<Object> real = priority();
            FaultyQueue lowest = new FaultyQueue();            // the lowest bucket, failing the compactor's and restorer's adds
            AtomicBoolean shortOfMemory = new AtomicBoolean(pressure);
            if (pressure) {
                all[LOWEST] = lowest;                          // the executor is new: the bucket is still empty
                lowest.failRestorer = true;
                lowest.failAdd = n -> shortOfMemory.get();
            }
            ToIntFunction<Object> priority = !pressure ? real : task -> {
                if (task != lowest.lastRemoved) return real.applyAsInt(task);
                lowest.lastRemoved = null;
                return ThreadLocalRandom.current().nextInt(3) == 0 ? LOWEST : real.applyAsInt(task);
            };
            Object monitor = new Object();
            java.util.concurrent.atomic.AtomicLong maxMonitorWait = new java.util.concurrent.atomic.AtomicLong();
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
                        // One token per position of a small run, as C2ME's runTaskWithLockArea builds them (one per chunk
                        // of the lock area, never twice the same): a task holding two equal tokens finds its own listener
                        // list in tryLock and retries forever, in FlowSched as shipped too.
                        Object tokens = Array.newInstance(tokenClass, 1 + random.nextInt(3));
                        int first = random.nextInt(16 - Array.getLength(tokens) + 1);
                        for (int t = 0; t < Array.getLength(tokens); t++)
                            Array.set(tokens, t, newToken.newInstance(0, (long) (first + t), worldgen));
                        Supplier<CompletableFuture<Object>> action = () -> {
                            long waitStart = System.nanoTime();
                            synchronized (monitor) {                 // as a finishing C2ME task takes its position's list
                                maxMonitorWait.accumulateAndGet(System.nanoTime() - waitStart, Math::max);
                            }
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
                lowest.target = Thread.currentThread();
                while (compact && !done.get())
                    for (int b = 0; b < all.length; b++) {
                        int left;
                        synchronized (monitor) {                     // as SchedulingManager around changePriority
                            left = FlowSchedQueuePurge.compact(all[b], count, b, priority, permits);
                        }
                        if (left < 0) java.util.concurrent.locks.LockSupport.parkNanos(50_000);   // not admitted: let others in
                    }
            });
            try {
                for (Thread producer : producerThreads) producer.join();
                if (pressure) {
                    Thread.sleep(1500);
                    assertTrue(FlowSchedQueuePurge.RESTORER.handedOff.get() > handedOffBefore, "a restore was handed off under pressure");
                    assertTrue(FlowSchedQueuePurge.RESTORER.retries.get() > retriesBefore, "the restorer met the pressure");
                    shortOfMemory.set(false);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                while (sum(runs) < total && System.nanoTime() < deadline) Thread.sleep(5);
            } finally {
                shortOfMemory.set(false);
                done.set(true);
                compactor.join();
            }
            awaitRestores("real executor");
            lowest.failRestorer = false;
            assertEquals(List.of(), failures);
            if (pressure)
                assertTrue(maxMonitorWait.get() < TimeUnit.MILLISECONDS.toNanos(500), "longest wait for the monitor " + maxMonitorWait.get() + " ns");
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
            return droppedSoFar() - droppedBefore;
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
