package dev.phoenium.chunk;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
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
 * and twice what its previous compaction kept is compacted: its current entries are polled one by one (count decremented
 * per entry actually polled, as {@code dequeue} does) and appended again (count incremented per entry, as {@code enqueue0}
 * does) unless the entry is superseded and one free permit can be retired with it. An entry is superseded when its task's
 * priority, read as FlowSched reads it, differs from the bucket. That never loses a task: every move of a task into a
 * bucket enqueues a fresh entry there after the CAS, so a task whose priority is not the bucket's either has its entry
 * elsewhere, is being run or has run, waits for a lock (and is re-enqueued when it frees), or gets a new entry if it moves
 * back. Retiring a permit per dropped entry keeps permits equal to entries, so idle workers still park on the semaphore
 * instead of spinning; when no permit is free a worker already holds it, and the entry is kept for that worker. Kept
 * entries keep their relative order and only fall behind entries enqueued during the compaction, which FlowSched does not
 * rely on (tasks are independent; locks order conflicting ones). There is no task-level cancellation in FlowSched (C2ME
 * cancels at the status level and the task still runs once), so nothing else can remove or re-run an entry.
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
    private static final AtomicInteger LARGEST_BUCKET = new AtomicInteger();
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
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (UNSUPPORTED.compareAndSet(false, true))
                org.slf4j.LoggerFactory.getLogger("Phoenium").error("S9-M6b FlowSched queue compaction disabled", failure);
        } finally {
            state.running.set(false);
        }
    }

    /**
     * Polls up to the bucket's current count of entries and appends them again, in order, except superseded entries for
     * which a free permit could be retired. Returns the number of entries kept. Only one thread compacts a given queue at
     * a time.
     */
    static <T> int compact(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray counts, int bucket, ToIntFunction<Object> priority,
                           Semaphore permits) {
        int budget = counts.get(bucket);
        ArrayList<T> keep = new ArrayList<>();
        int dropped = 0, held = 0;
        for (int i = 0; i < budget; i++) {
            T task = queue.poll();
            if (task == null) break;
            counts.decrementAndGet(bucket);
            if (priority.applyAsInt(task) == bucket) keep.add(task);
            else if (permits.tryAcquire()) dropped++;
            else { keep.add(task); held++; }
        }
        for (T task : keep) {
            queue.add(task);
            counts.incrementAndGet(bucket);
        }
        COMPACTIONS.increment();
        DROPPED.add(dropped);
        KEPT.add(keep.size());
        HELD.add(held);
        return keep.size();
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
            + " c2meQueueLargestBucket=" + LARGEST_BUCKET.get();
    }
}
