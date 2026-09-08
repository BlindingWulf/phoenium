package dev.phoenium.scheduler;

import dev.phoenium.ownership.OwnershipContext;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Only the coordinator dispatches finite, already claimed cohorts. No owner pumps
 * this executor, waits for a peer, or executes a rejected task as a fallback. */
final class BoundedGameplayWorkers {
    private final int count;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean batchActive = new AtomicBoolean();
    private volatile boolean stopped;
    private final java.util.concurrent.locks.ReentrantLock lifecycle = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition idle = lifecycle.newCondition();

    BoundedGameplayWorkers(int count) {
        this.count = count;
        AtomicInteger ids = new AtomicInteger();
        executor = count == 1 ? null : new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(count), body -> {
                Thread worker = new Thread(body, "phoenium-gameplay-" + ids.incrementAndGet());
                worker.setDaemon(false);
                return worker;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    void begin() {
        lifecycle.lock(); try {
            if (stopped || !batchActive.compareAndSet(false, true))
                throw new IllegalStateException("gameplay batch admission is stopped or already active");
        } finally { lifecycle.unlock(); }
    }
    void finish() {
        lifecycle.lock(); try { batchActive.set(false); idle.signalAll(); }
        finally { lifecycle.unlock(); }
    }
    void execute(List<Runnable> selected) {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("owner attempted to wait for gameplay workers");
        if (selected.size() > count) throw new IllegalArgumentException("gameplay cohort exceeds worker capacity");
        if (!batchActive.get()) throw new IllegalStateException("gameplay cohort was not admitted");
            if (executor == null) { selected.forEach(Runnable::run); return; }
            CountDownLatch settled = new CountDownLatch(selected.size());
            int submitted = 0;
            Throwable failure = null;
            try {
                for (Runnable body : selected) {
                    executor.execute(() -> { try { body.run(); } finally { settled.countDown(); } });
                    submitted++;
                }
            } catch (Throwable problem) {
                failure = problem;
                for (int i = submitted; i < selected.size(); i++) settled.countDown();
            }
            // Interruption closes no claim and cannot turn partial work into successful
            // cleanup. Join every admitted body before restoring the interrupt/failure.
            boolean interrupted = false;
            while (true) {
                try { settled.await(); break; }
                catch (InterruptedException problem) {
                    interrupted = true;
                    if (failure == null) failure = problem; else failure.addSuppressed(problem);
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (failure != null) throw new IllegalStateException("gameplay worker submission/join failed", failure);
    }

    void stop() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("worker shutdown holds owner authority");
        lifecycle.lock(); try {
            stopped = true;
            while (batchActive.get()) idle.awaitUninterruptibly();
        } finally { lifecycle.unlock(); }
        if (executor == null) return;
        executor.shutdown();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try { executor.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException problem) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    int queued() { return executor == null ? 0 : executor.getQueue().size(); }
    int threads() { return executor == null ? 0 : executor.getPoolSize(); }
    boolean batchActive() { return batchActive.get(); }
}
