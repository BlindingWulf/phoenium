package dev.phoenium.engine;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * S9-E1: the tile threads. The server thread publishes one colour's tiles as a {@link Job}, runs tiles itself, and
 * returns when every tile of the job has finished; a tile thread takes tiles from the job's shared index (work
 * stealing). Between jobs of one phase the threads spin briefly, then park until the next job is published.
 */
final class EngineWorkerPool {
    /** About 0.2-0.5 ms of Thread.onSpinWait before a waiting thread parks. */
    private static final int SPINS = 8_192;

    static final class Job {
        final TileWork[] tiles;
        final int count;
        final Runner runner;
        final Thread coordinator = Thread.currentThread();
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger remaining;
        /** The first tile failure; later ones are attached to it as suppressed. */
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Job(TileWork[] tiles, int count, Runner runner) {
            this.tiles = tiles;
            this.count = count;
            this.runner = runner;
            this.remaining = new AtomicInteger(count);
        }

        void fail(Throwable cause) {
            if (!failure.compareAndSet(null, cause)) {
                Throwable first = failure.get();
                if (first != cause) first.addSuppressed(cause);
            }
        }
    }

    /** Runs one tile on the calling thread with the calling thread's context. */
    interface Runner {
        void run(TileWork tile, TileContext context);
    }

    final class Worker extends Thread {
        final TileContext context = new TileContext();

        Worker(int index) {
            super("Phoenium Tile " + index);
            setDaemon(true);
        }

        @Override
        public void run() {
            Job seen = null;
            int idle = 0;
            while (!stopping) {
                Job published = job;
                if (published != null && published != seen) {
                    seen = published;
                    work(published, context);
                    idle = 0;
                } else if (idle < SPINS) {
                    idle++;
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(this, 1_000_000L);
                }
            }
        }
    }

    private final Worker[] workers;
    private volatile Job job;
    private volatile boolean stopping;

    EngineWorkerPool(int threads) {
        workers = new Worker[threads];
        for (int i = 0; i < threads; i++) workers[i] = new Worker(i + 1);
        for (Worker worker : workers) worker.start();
    }

    int threads() { return workers.length; }

    /** Server thread: runs every tile of the job (here and on the tile threads); rethrows the first tile failure. */
    void run(Job published, TileContext own) {
        job = published;
        for (Worker worker : workers) LockSupport.unpark(worker);
        work(published, own);
        int spins = 0;
        while (published.remaining.get() != 0) {
            if (spins < SPINS) {
                spins++;
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(this, 20_000L);
            }
        }
        job = null;
        Throwable failure = published.failure.get();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("tile failed", failure);
    }

    private static void work(Job job, TileContext context) {
        int index;
        while ((index = job.next.getAndIncrement()) < job.count) {
            try {
                if (job.failure.get() == null) job.runner.run(job.tiles[index], context);
            } catch (Throwable failure) {
                job.fail(failure);
            } finally {
                if (job.remaining.decrementAndGet() == 0) LockSupport.unpark(job.coordinator);
            }
        }
    }

    void stop() {
        stopping = true;
        for (Worker worker : workers) LockSupport.unpark(worker);
        for (Worker worker : workers) {
            try {
                worker.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static boolean isWorker(Thread thread) { return thread instanceof Worker; }

    static TileContext contextOf(Thread thread) { return ((Worker) thread).context; }
}
