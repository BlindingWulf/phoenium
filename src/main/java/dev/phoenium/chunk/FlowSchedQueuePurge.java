package dev.phoenium.chunk;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToIntFunction;

/**
 * S9-M6b (MEM-6, D40): bounds the superseded entries of C2ME's FlowSched executor queue.
 *
 * <p>FlowSched's {@code ExecutorManager} keeps a {@code BucketTaskPriorityQueue} (one ConcurrentLinkedQueue per priority
 * bucket plus a per-bucket entry count) and one semaphore permit per queued entry: {@code reschedule0} releases a permit
 * whenever {@code requeue} enqueued an entry, and a worker acquires a permit and then polls until it gets an entry (a
 * task or TOMBSTONE). Changing a task's priority CASes its {@code priority} field and enqueues a fresh entry in the new
 * bucket; the old entry stays behind until its bucket is polled. C2ME enqueues worldgen tasks before their level priority
 * is known (the lowest bucket), which is polled only when every other bucket is empty, so under sustained generation its
 * superseded entries, and the finished tasks they reference, accumulate without bound (MEM-6). D39 made each such task
 * small; this bounds how many there are.
 *
 * <p>Every so many enqueues an executor checks its bucket counts; a bucket holding more than {@link #THRESHOLD} entries
 * and twice what its previous compaction kept is compacted IN PLACE: entries are scanned with the queue's weakly
 * consistent iterator and never taken out, except a superseded entry, which is removed by the queue's own atomic removal
 * (a CAS on one node holding that task: exactly one of the compactor and a polling worker gets it) paired with retiring
 * one free permit. An entry is superseded when its task's priority, read as FlowSched reads it, differs from the bucket.
 * Every move of a task into a bucket enqueues a fresh entry there after the priority CAS, so a superseded task either has
 * its entry elsewhere, is running or has run, waits for a lock (and is re-enqueued when it frees), or gets a new entry
 * when it moves back. One race remains: the task moves back into this bucket between the read and the removal, and the
 * removal takes its fresh entry (an older one was polled meanwhile); so the priority is read again after every removal
 * and, if the task now belongs to this bucket (or the read fails), the entry is restored exactly as FlowSched enqueues
 * one (entry, count, permit). An extra entry only becomes a tombstone.
 *
 * <p>Exception safety, step by step: nothing changes before the removal (scan, priority read, permit tryAcquire); a
 * removal that does not happen (lost race or failure) gives its permit back in a finally; after a removal the count is
 * decremented (cannot fail) and the re-check decides between dropped and restored, defaulting to restore if it fails; a
 * restore retries an allocation failure until the entry is back. So a failure at any step stops the compaction and loses
 * no task, duplicates none (FlowSched's CAS still runs a task once), and keeps permits equal to entries and counts equal to
 * the bucket's entries. Retiring a permit per removed entry keeps idle workers parked on the semaphore instead of
 * spinning; when no permit is free a worker already holds it and the entry stays for that worker. Kept entries keep their
 * order. Removal relies on identity equality (FlowSched/C2ME tasks do not override equals; checked per class).
 * There is no task-level cancellation in FlowSched (C2ME cancels at the status level and the task still runs once).
 * {@code -Dphoenium.c2me.purgeStaleQueueEntries=false} disables it.
 */
public final class FlowSchedQueuePurge {
    private FlowSchedQueuePurge() { }

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("phoenium.c2me.purgeStaleQueueEntries"));
    /** Smallest bucket size that is compacted. */
    static final int THRESHOLD = Math.max(64, Integer.getInteger("phoenium.c2me.purgeThreshold", 4096));
    /** Buckets are checked once per this many enqueues on an executor (power of two). */
    static final int CHECK_EVERY = 1024;
    private static final String TASK = "com.ishland.flowsched.executor.Task";

    private static final LongAdder COMPACTIONS = new LongAdder();
    private static final LongAdder DROPPED = new LongAdder();
    private static final LongAdder KEPT = new LongAdder();
    private static final LongAdder HELD = new LongAdder();
    private static final LongAdder RESTORED = new LongAdder();
    private static final LongAdder RESTORE_RETRIES = new LongAdder();
    private static final AtomicInteger LARGEST_BUCKET = new AtomicInteger();
    /** Whether entries of a task class can be removed by identity ({@code ConcurrentLinkedQueue.remove} uses equals). */
    private static final ClassValue<Boolean> IDENTITY = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("equals", Object.class).getDeclaringClass() == Object.class;
            } catch (NoSuchMethodException impossible) {
                return false;
            }
        }
    };
    private static final AtomicBoolean UNSUPPORTED = new AtomicBoolean();

    /** One per executor: enqueue counter, single compactor, the queue's structures and what each bucket kept last time. */
    public static final class State {
        private final AtomicInteger enqueues = new AtomicInteger();
        private final AtomicBoolean running = new AtomicBoolean();
        private ConcurrentLinkedQueue<?>[] buckets;
        private AtomicIntegerArray counts;
        private ToIntFunction<Object> priority;
        private int[] kept;

        /** Binds to a FlowSched {@code BucketTaskPriorityQueue}. */
        void bind(Object queue) throws ReflectiveOperationException {
            ConcurrentLinkedQueue<?>[] buckets = (ConcurrentLinkedQueue<?>[]) field(queue.getClass(), "priorities").get(queue);
            counts = (AtomicIntegerArray) field(queue.getClass(), "taskCount").get(queue);
            priority = priorityReader(queue.getClass().getClassLoader());
            kept = new int[buckets.length];
            this.buckets = buckets;
        }
    }

    /** Called after each {@code ExecutorManager.reschedule0} of {@code executor}; compacts oversized buckets now and then. */
    public static void afterEnqueue(State state, Object executor, Semaphore permits) {
        if ((state.enqueues.incrementAndGet() & (CHECK_EVERY - 1)) != 0 || UNSUPPORTED.get()) return;
        if (!state.running.compareAndSet(false, true)) return;
        try {
            if (state.buckets == null) state.bind(field(executor.getClass(), "globalWorkQueue").get(executor));
            for (int bucket = 0; bucket < state.buckets.length; bucket++) {
                int count = state.counts.get(bucket);
                LARGEST_BUCKET.accumulateAndGet(count, Math::max);
                if (count > Math.max(THRESHOLD, 2 * state.kept[bucket]))
                    state.kept[bucket] = compact(state.buckets[bucket], state.counts, bucket, state.priority, permits);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (UNSUPPORTED.compareAndSet(false, true))
                org.slf4j.LoggerFactory.getLogger("Phoenium").error("S9-M6b FlowSched queue compaction disabled", failure);
        } finally {
            state.running.set(false);
        }
    }

    /**
     * Removes, in place, up to the bucket's current count of scanned entries the superseded ones for which a free permit
     * can be retired (see the class comment for the protocol and its exception safety). Returns the bucket's count
     * afterwards. Safe to run concurrently with FlowSched's own enqueue, re-prioritise and poll, and with another compactor.
     */
    static <T> int compact(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray counts, int bucket, ToIntFunction<Object> priority,
                           Semaphore permits) {
        int budget = counts.get(bucket);
        int scanned = 0, dropped = 0, held = 0, restored = 0;
        try {
            for (Iterator<T> entries = queue.iterator(); scanned < budget && entries.hasNext(); scanned++) {
                T task = entries.next();
                if (!IDENTITY.get(task.getClass()) || priority.applyAsInt(task) == bucket) continue;   // live: stays
                if (!permits.tryAcquire()) {        // a worker holds every free permit: the entry stays for it
                    held++;
                    continue;
                }
                boolean removed = false;
                try {
                    removed = queue.remove(task);   // atomic per node: a worker polling it concurrently gets it or not
                } finally {
                    if (!removed) permits.release();
                }
                if (!removed) continue;
                counts.decrementAndGet(bucket);
                boolean restore = true;              // if the re-check fails, assume the entry was live
                try {
                    restore = priority.applyAsInt(task) == bucket;
                } finally {
                    if (restore) {
                        restore(queue, counts, bucket, task, permits);
                        restored++;
                    } else {
                        dropped++;
                    }
                }
            }
        } finally {
            COMPACTIONS.increment();
            DROPPED.add(dropped);
            KEPT.add(scanned - dropped);
            HELD.add(held);
            RESTORED.add(restored);
        }
        return counts.get(bucket);
    }

    /** Puts back a removed entry as FlowSched enqueues one (entry, then count, then one permit); retries until it is back. */
    static <T> void restore(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray counts, int bucket, T task, Semaphore permits) {
        for (long backoffNanos = 100_000; ; backoffNanos = Math.min(backoffNanos * 2, 50_000_000)) {
            try {
                queue.add(task);
                break;
            } catch (OutOfMemoryError failure) {   // the only way add fails: wait for memory, never drop a live entry
                RESTORE_RETRIES.increment();
                java.util.concurrent.locks.LockSupport.parkNanos(backoffNanos);
            }
        }
        counts.incrementAndGet(bucket);
        permits.release();
    }

    /** Reads FlowSched's {@code Task.priority} with the acquire semantics its own poll and re-prioritise use. */
    static ToIntFunction<Object> priorityReader(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> task = Class.forName(TASK, false, loader);
        VarHandle priority = MethodHandles.privateLookupIn(task, MethodHandles.lookup()).findVarHandle(task, "priority", int.class);
        return value -> (int) priority.getAcquire(value);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    public static String counters() {
        return "c2meQueuePurge=" + (ENABLED && !UNSUPPORTED.get()) + " c2meQueueCompactions=" + COMPACTIONS.sum()
            + " c2meQueueDropped=" + DROPPED.sum() + " c2meQueueKept=" + KEPT.sum() + " c2meQueueHeldForWorker=" + HELD.sum()
            + " c2meQueueRestored=" + RESTORED.sum() + " c2meQueueRestoreRetries=" + RESTORE_RETRIES.sum()
            + " c2meQueueLargestBucket=" + LARGEST_BUCKET.get();
    }
}
