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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
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
 * A pass spends at most {@link #WORK_BUDGET} work units (scan steps plus the entries each removal walks past) and then
 * stops ({@link #PARTIAL}); the next check continues.
 * Every move of a task into a bucket enqueues a fresh entry there after the priority CAS, so a superseded task either has
 * its entry elsewhere, is running or has run, waits for a lock (and is re-enqueued when it frees), or gets a new entry
 * when it moves back. One race remains: the task moves back into this bucket between the read and the removal, and the
 * removal takes its fresh entry (an older one was polled meanwhile); so the priority is read again after every removal
 * and, if the task now belongs to this bucket (or the read fails), the entry is restored exactly as FlowSched enqueues
 * one (entry, count, permit). An extra entry only becomes a tombstone.
 *
 * <p>Exception safety, step by step: nothing changes before the removal (scan, priority read, permit tryAcquire); a
 * removal that does not happen (lost race or failure) gives its permit back in a finally; after a removal the count is
 * decremented (cannot fail) and the re-check decides between dropped and restored, defaulting to restore if it fails. A
 * restore that cannot allocate its entry (or fails with any other error, e.g. StackOverflowError) hands it to the
 * {@link Restorer} thread, which puts it back as soon as memory allows, and the compaction stops. The hook swallows and
 * counts every failure of this optional work; a VirtualMachineError after the entry is linked but before add returns is
 * the one residual case (the restorer adds a second entry, so the bucket holds one entry more than its count and permits). So a failure at any step stops the compaction and loses no task, duplicates none
 * (FlowSched's CAS still runs a task once), and keeps permits equal to entries and counts equal to the bucket's entries.
 * The compacting thread never waits for memory: it may be inside C2ME code holding a monitor the workers need. Retiring a
 * permit per removed entry keeps idle workers parked on the semaphore instead of spinning; when no permit is free a worker
 * already holds it and the entry stays for that worker. Kept entries keep their order; a restored entry goes to the tail,
 * as FlowSched's own re-enqueue puts it. Removal relies on identity equality (FlowSched/C2ME tasks do not override equals;
 * checked per class). There is no task-level cancellation in FlowSched (C2ME cancels at the status level and the task
 * still runs once). {@code -Dphoenium.c2me.purgeStaleQueueEntries=false} disables it.
 */
public final class FlowSchedQueuePurge {
    private FlowSchedQueuePurge() { }

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("phoenium.c2me.purgeStaleQueueEntries"));
    /** Smallest bucket size that is compacted. */
    static final int THRESHOLD = Math.max(64, Integer.getInteger("phoenium.c2me.purgeThreshold", 4096));
    /** Buckets are checked once per this many enqueues on an executor (power of two). */
    static final int CHECK_EVERY = 1024;
    /** Work units (scan steps plus entries the removals walk past) one compaction pass may spend; see compact. */
    static final long WORK_BUDGET = Math.max(1024L, Long.getLong("phoenium.c2me.purgeWorkBudget", 1L << 20));
    /** Result of a compaction pass that stopped at its work budget before finishing its scan. */
    static final int PARTIAL = -2;
    private static final String TASK = "com.ishland.flowsched.executor.Task";

    // AtomicLong, not LongAdder: updating them never allocates, so no counter can fail under memory pressure.
    private static final AtomicLong COMPACTIONS = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong KEPT = new AtomicLong();
    private static final AtomicLong HELD = new AtomicLong();
    private static final AtomicLong RESTORED = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong ALLOCATION_FAILURES = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong PARTIALS = new AtomicLong();
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
    static final Restorer RESTORER = new Restorer(64, "Phoenium FlowSched restorer");

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

    /**
     * Puts back, as soon as memory allows, entries whose restore could not allocate. The compacting thread must not wait
     * for memory itself: C2ME's SchedulingManager.updatePriorityInternal calls changePriority (and so this hook) while
     * holding a position's task-list monitor, which a worker takes when a task of that position finishes, so waiting there
     * would stall workers for as long as memory stays short. A handoff allocates nothing (a slot CAS, stores into
     * preallocated arrays, an unpark). Until its entry is back the task waits, like a task whose own enqueue waits for
     * memory; it can still be moved to, and run from, another bucket meanwhile (the restored entry is then a tombstone).
     *
     * <p>A slot is always free: compactions are admitted only while no restore is pending and fewer than the slot count
     * run, and each hands off at most once and then stops. Every pending handoff therefore comes from a compaction that
     * was already running when the last of those compactions was admitted (which saw none pending), so there are at most
     * as many as could run at once.
     */
    static final class Restorer {
        private static final int FREE = 0, CLAIMED = 1, READY = 2;
        private static final long MIN_BACKOFF_NANOS = 100_000, MAX_BACKOFF_NANOS = 50_000_000;
        private final AtomicIntegerArray slots;
        private final Object[] tasks;
        private final ConcurrentLinkedQueue<?>[] queues;
        private final AtomicIntegerArray[] counts;
        private final int[] buckets;
        private final Semaphore[] permits;
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        final AtomicLong handedOff = new AtomicLong(), retries = new AtomicLong(), restored = new AtomicLong(), errors = new AtomicLong();
        final Thread thread;
        private final AtomicBoolean starting = new AtomicBoolean();
        private volatile boolean started;

        Restorer(int slotCount, String name) {
            slots = new AtomicIntegerArray(slotCount);
            tasks = new Object[slotCount];
            queues = new ConcurrentLinkedQueue<?>[slotCount];
            counts = new AtomicIntegerArray[slotCount];
            buckets = new int[slotCount];
            permits = new Semaphore[slotCount];
            thread = new Thread(this::run, name);
            thread.setDaemon(true);
        }

        /** Entries handed off and not yet back. */
        int pending() {
            return pending.get();
        }

        /** Admits a compaction unless a restore is pending or as many compactions as slots are running. */
        boolean enter() {
            if (inFlight.incrementAndGet() <= slots.length() && pending.get() == 0) return true;
            inFlight.decrementAndGet();
            return false;
        }

        void exit() {
            inFlight.decrementAndGet();
        }

        /**
         * Starts the thread on the first compaction, before that compaction changes anything, so a failure to start
         * (e.g. no memory for a native thread) changes nothing and a later compaction tries again. Lock-free: while
         * another thread is starting it this returns false and the caller is not admitted this time.
         */
        boolean start() {
            if (started) return true;
            if (!starting.compareAndSet(false, true)) return false;
            try {
                thread.start();
                started = true;
            } finally {
                if (!started) starting.set(false);
            }
            return true;
        }

        /** Takes over an entry whose restore could not allocate: the thread puts back entry, count and permit later. */
        void handOff(ConcurrentLinkedQueue<?> queue, AtomicIntegerArray count, int bucket, Object task, Semaphore permit) {
            pending.incrementAndGet();             // first: from here on no compaction is admitted
            for (int slot = 0; slot < slots.length(); slot++) {
                if (!slots.compareAndSet(slot, FREE, CLAIMED)) continue;
                tasks[slot] = task;
                queues[slot] = queue;
                counts[slot] = count;
                buckets[slot] = bucket;
                permits[slot] = permit;
                slots.set(slot, READY);            // publishes the stores above to the thread
                handedOff.incrementAndGet();
                LockSupport.unpark(thread);
                return;
            }
            try {                                  // unreachable while compactions go through enter() (see above)
                waitAndRestore(queue, count, bucket, task, permit);
            } finally {
                pending.decrementAndGet();
            }
        }

        private void run() {
            long backoffNanos = MIN_BACKOFF_NANOS;
            for (;;) {
                try {
                    boolean waiting = false;
                    for (int slot = 0; slot < slots.length(); slot++)
                        if (slots.get(slot) == READY && !putBack(slot)) waiting = true;
                    Thread.interrupted();          // a stray interrupt must not turn the waits below into a spin
                    if (waiting) {
                        LockSupport.parkNanos(this, backoffNanos);
                        backoffNanos = Math.min(backoffNanos * 2, MAX_BACKOFF_NANOS);
                    } else {
                        backoffNanos = MIN_BACKOFF_NANOS;
                        if (pending.get() == 0) LockSupport.park(this);
                        else LockSupport.parkNanos(this, MIN_BACKOFF_NANOS);   // a handoff is still being written
                    }
                } catch (Throwable unexpected) {   // never stop while entries may be waiting
                    failed(unexpected);
                    LockSupport.parkNanos(this, MAX_BACKOFF_NANOS);
                }
            }
        }

        /** One attempt to put back the entry of {@code slot}; false if it still cannot be allocated. */
        @SuppressWarnings("unchecked")
        private boolean putBack(int slot) {
            try {
                ((ConcurrentLinkedQueue<Object>) queues[slot]).add(tasks[slot]);
            } catch (OutOfMemoryError failure) {
                retries.incrementAndGet();
                return false;
            } catch (Throwable failure) {          // D40 review R4: counted and visible, then retried like memory
                failed(failure);
                retries.incrementAndGet();
                return false;
            }
            try {
                counts[slot].incrementAndGet(buckets[slot]);
            } finally {
                permits[slot].release();
                tasks[slot] = null;
                queues[slot] = null;
                counts[slot] = null;
                permits[slot] = null;
                restored.incrementAndGet();
                slots.set(slot, FREE);
                pending.decrementAndGet();         // last: once nothing is pending, every entry, count and permit is back
            }
            return true;
        }

        /** Counts a restorer failure other than OutOfMemoryError; the first one is logged (a pending entry blocks compaction). */
        private void failed(Throwable failure) {
            if (errors.getAndIncrement() == 0) {
                try {
                    org.slf4j.LoggerFactory.getLogger("Phoenium").error("S9-M6b FlowSched restorer failure (retrying)", failure);
                } catch (Throwable ignored) {      // logging is best effort here
                }
            }
        }

        /** Last resort for a handoff without a free slot: waits here until the entry is back (nothing allocates meanwhile). */
        private <T> void waitAndRestore(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray count, int bucket, Object task,
                                        Semaphore permit) {
            boolean interrupted = false;
            for (long backoffNanos = MIN_BACKOFF_NANOS; ; backoffNanos = Math.min(backoffNanos * 2, MAX_BACKOFF_NANOS)) {
                try {
                    @SuppressWarnings("unchecked") T entry = (T) task;
                    queue.add(entry);
                    break;
                } catch (OutOfMemoryError failure) {
                    retries.incrementAndGet();
                    interrupted |= Thread.interrupted();
                    LockSupport.parkNanos(backoffNanos);
                }
            }
            try {
                count.incrementAndGet(bucket);
            } finally {
                permit.release();
            }
            if (interrupted) Thread.currentThread().interrupt();
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
                if (count > Math.max(THRESHOLD, 2 * state.kept[bucket])) {
                    int kept = compact(state.buckets[bucket], state.counts, bucket, state.priority, permits);
                    if (kept == -1) break;         // not admitted (a restore is pending): a later check retries
                    if (kept == PARTIAL) continue; // out of budget: keep the previous size, so the next check continues
                    state.kept[bucket] = kept;
                }
            }
        } catch (OutOfMemoryError failure) {
            // Optional work never passes its own allocation failure on to FlowSched's caller (C2ME code mid-update). Every
            // point of a compaction that can throw leaves the queue consistent, and a later check simply tries again.
            ALLOCATION_FAILURES.incrementAndGet();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (UNSUPPORTED.compareAndSet(false, true))
                org.slf4j.LoggerFactory.getLogger("Phoenium").error("S9-M6b FlowSched queue compaction disabled", failure);
        } catch (Throwable failure) {
            // D40 review R2: nor any other error (StackOverflowError deep in C2ME frames, InternalError); counted, retried.
            ERRORS.incrementAndGet();
        } finally {
            state.running.set(false);
        }
    }

    static <T> int compact(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray counts, int bucket, ToIntFunction<Object> priority,
                           Semaphore permits) {
        return compact(queue, counts, bucket, priority, permits, RESTORER);
    }

    static <T> int compact(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray counts, int bucket, ToIntFunction<Object> priority,
                           Semaphore permits, Restorer restorer) {
        return compact(queue, counts, bucket, priority, permits, restorer, WORK_BUDGET);
    }

    /**
     * Removes, in place, up to the bucket's current count of scanned entries the superseded ones for which a free permit
     * can be retired (see the class comment for the protocol and its exception safety). Returns the bucket's count
     * afterwards, -1 if the compaction was not admitted (see {@link Restorer}), or {@link #PARTIAL} if it stopped at its
     * work budget (a later check continues). Safe to run concurrently with FlowSched's own enqueue, re-prioritise and
     * poll, and with another compactor.
     *
     * <p>Work budget (D40 review R1): this runs on the enqueuing thread, possibly inside C2ME monitors, and the queue's
     * atomic removal walks from the head, so each removal costs about the entries still ahead of it. Scan steps and those
     * walks are counted against {@code workBudget}; a removal that would exceed it is not started and the pass ends.
     */
    static <T> int compact(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray counts, int bucket, ToIntFunction<Object> priority,
                           Semaphore permits, Restorer restorer, long workBudget) {
        if (!restorer.start() || !restorer.enter()) {   // the restorer runs before any entry can be handed to it
            SKIPPED.incrementAndGet();
            return -1;
        }
        int budget = counts.get(bucket);
        int scanned = 0, dropped = 0, held = 0, restored = 0, handedOff = 0;
        long work = 0;
        boolean partial = false;
        try {
            for (Iterator<T> entries = queue.iterator(); handedOff == 0 && scanned < budget && entries.hasNext(); scanned++) {
                T task = entries.next();
                work++;
                if (!IDENTITY.get(task.getClass()) || priority.applyAsInt(task) == bucket) continue;   // live: stays
                long walk = scanned - dropped - restored;  // entries ahead of this one that the removal walks past
                if (work + walk > workBudget) {
                    partial = true;
                    break;
                }
                work += walk;
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
                    if (!restore) dropped++;
                    else if (restore(queue, counts, bucket, task, permits, restorer)) restored++;
                    else handedOff++;                // memory is short: the restorer has it, and this compaction stops
                }
            }
        } finally {
            restorer.exit();
            COMPACTIONS.incrementAndGet();
            DROPPED.addAndGet(dropped);
            KEPT.addAndGet(scanned - dropped);
            HELD.addAndGet(held);
            RESTORED.addAndGet(restored);
            if (partial) PARTIALS.incrementAndGet();
        }
        return partial ? PARTIAL : counts.get(bucket);
    }

    /**
     * Puts a removed entry back as FlowSched enqueues one (entry, then count, then one permit), or, if the entry cannot be
     * allocated now, hands it to {@code restorer}, which does so as soon as memory allows. Never waits; returns whether
     * the entry is back already.
     */
    static <T> boolean restore(ConcurrentLinkedQueue<T> queue, AtomicIntegerArray counts, int bucket, T task, Semaphore permits,
                               Restorer restorer) {
        try {
            queue.add(task);
        } catch (Throwable failure) {               // OutOfMemoryError, or (D40 review R2) StackOverflowError/InternalError:
            restorer.handOff(queue, counts, bucket, task, permits);   // the removed entry must never be stranded
            return false;
        }
        try {
            counts.incrementAndGet(bucket);
        } finally {
            permits.release();
        }
        return true;
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
        return "c2meQueuePurge=" + (ENABLED && !UNSUPPORTED.get()) + " c2meQueueCompactions=" + COMPACTIONS.get()
            + " c2meQueueDropped=" + DROPPED.get() + " c2meQueueKept=" + KEPT.get() + " c2meQueueHeldForWorker=" + HELD.get()
            + " c2meQueueRestored=" + RESTORED.get() + " c2meQueueRestoreHandedOff=" + RESTORER.handedOff.get()
            + " c2meQueueRestoredLate=" + RESTORER.restored.get() + " c2meQueueRestoreRetries=" + RESTORER.retries.get()
            + " c2meQueueRestorePending=" + RESTORER.pending() + " c2meQueueRestoreErrors=" + RESTORER.errors.get()
            + " c2meQueueSkipped=" + SKIPPED.get() + " c2meQueuePartial=" + PARTIALS.get()
            + " c2meQueueAllocationFailures=" + ALLOCATION_FAILURES.get() + " c2meQueueErrors=" + ERRORS.get()
            + " c2meQueueLargestBucket=" + LARGEST_BUCKET.get();
    }
}
