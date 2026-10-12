package dev.phoenium.chunk;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToIntFunction;

/**
 * S9-M6b (MEM-6, D40): bounds the superseded entries of C2ME's FlowSched bucket queue.
 *
 * <p>FlowSched's {@code BucketTaskPriorityQueue} keeps one ConcurrentLinkedQueue per priority bucket plus a per-bucket
 * entry count. Changing a task's priority CASes the task's {@code priority} field and enqueues a fresh entry in the new
 * bucket; the old entry stays where it was and is dropped only when that bucket is polled ({@code pollAtPriority} fails,
 * the worker gets TOMBSTONE). C2ME enqueues worldgen tasks before their level priority is known (the lowest bucket),
 * which is polled only when every other bucket is empty, so under sustained generation its superseded entries, and the
 * finished tasks they reference, accumulate without bound (MEM-6). D39 made each such task small; this bounds how many
 * there are.
 *
 * <p>Every so many enqueues a queue checks its bucket counts; a bucket holding more than {@link #THRESHOLD} entries and
 * twice what its previous compaction kept is compacted: its current entries are polled one by one (as a worker would,
 * count decremented per entry actually polled) and those whose task still has that bucket's priority are appended again
 * (count incremented per entry added, as {@code enqueue0} does). An entry is dropped only if its task's priority, read
 * as FlowSched reads it, differs from the bucket. That never loses a task: every move of a task into a bucket enqueues a
 * fresh entry there after the CAS, so a task whose priority is not the bucket's either has its entry elsewhere, is being
 * run or waits for a lock (and is re-enqueued when the lock frees), or will get a new entry if it moves back. Kept tasks
 * lose their FIFO place behind entries enqueued during the compaction, which FlowSched does not rely on (tasks are
 * independent; locks order conflicting ones). {@code -Dphoenium.c2me.purgeStaleQueueEntries=false} disables it.
 */
public final class FlowSchedQueuePurge {
    private FlowSchedQueuePurge() { }

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("phoenium.c2me.purgeStaleQueueEntries"));
    /** Smallest bucket size that is compacted. */
    static final int THRESHOLD = Math.max(64, Integer.getInteger("phoenium.c2me.purgeThreshold", 4096));
    /** Buckets are checked once per this many enqueues on a queue (power of two). */
    static final int CHECK_EVERY = 1024;
    private static final String TASK = "com.ishland.flowsched.executor.Task";

    private static final LongAdder COMPACTIONS = new LongAdder();
    private static final LongAdder DROPPED = new LongAdder();
    private static final LongAdder KEPT = new LongAdder();
    private static final AtomicInteger LARGEST_BUCKET = new AtomicInteger();
    private static final AtomicBoolean UNSUPPORTED = new AtomicBoolean();

    /** One per queue: enqueue counter, single compactor, and what each bucket kept last time. */
    public static final class State {
        private final AtomicInteger enqueues = new AtomicInteger();
        private final AtomicBoolean running = new AtomicBoolean();
        private int[] kept;
        private ToIntFunction<Object> priority;
    }

    /** Called after each enqueue of the queue owning {@code state}; compacts oversized buckets now and then. */
    public static void afterEnqueue(State state, ConcurrentLinkedQueue<?>[] buckets, AtomicIntegerArray counts,
                                    ClassLoader loader) {
        if ((state.enqueues.incrementAndGet() & (CHECK_EVERY - 1)) != 0 || UNSUPPORTED.get()) return;
        if (!state.running.compareAndSet(false, true)) return;
        try {
            if (state.priority == null) {
                state.priority = priorityReader(loader);
                state.kept = new int[buckets.length];
            }
            for (int bucket = 0; bucket < buckets.length; bucket++) {
                int count = counts.get(bucket);
                LARGEST_BUCKET.accumulateAndGet(count, Math::max);
                if (count > Math.max(THRESHOLD, 2 * state.kept[bucket]))
                    state.kept[bucket] = compact(buckets[bucket], counts, bucket, state.priority);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (UNSUPPORTED.compareAndSet(false, true))
                org.slf4j.LoggerFactory.getLogger("Phoenium").error("S9-M6b FlowSched queue compaction disabled", failure);
        } finally {
            state.running.set(false);
        }
    }

    /**
     * Polls up to the bucket's current count of entries and appends again those whose task still has the bucket's
     * priority. Returns the number of entries kept. Only one thread compacts a given queue at a time.
     */
    static <T> int compact(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray counts, int bucket, ToIntFunction<Object> priority) {
        int budget = counts.get(bucket);
        ArrayList<T> keep = new ArrayList<>();
        int dropped = 0;
        for (int i = 0; i < budget; i++) {
            T task = queue.poll();
            if (task == null) break;
            counts.decrementAndGet(bucket);
            if (priority.applyAsInt(task) == bucket) keep.add(task);
            else dropped++;
        }
        for (T task : keep) {
            queue.add(task);
            counts.incrementAndGet(bucket);
        }
        COMPACTIONS.increment();
        DROPPED.add(dropped);
        KEPT.add(keep.size());
        return keep.size();
    }

    /** Reads FlowSched's {@code Task.priority} with the acquire semantics its own poll and re-prioritise use. */
    static ToIntFunction<Object> priorityReader(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> task = Class.forName(TASK, false, loader);
        VarHandle priority = MethodHandles.privateLookupIn(task, MethodHandles.lookup()).findVarHandle(task, "priority", int.class);
        return value -> (int) priority.getAcquire(value);
    }

    public static String counters() {
        return "c2meQueuePurge=" + (ENABLED && !UNSUPPORTED.get()) + " c2meQueueCompactions=" + COMPACTIONS.sum()
            + " c2meQueueDropped=" + DROPPED.sum() + " c2meQueueKept=" + KEPT.sum() + " c2meQueueLargestBucket=" + LARGEST_BUCKET.get();
    }
}
