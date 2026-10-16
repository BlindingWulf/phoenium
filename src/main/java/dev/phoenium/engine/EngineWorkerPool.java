package dev.phoenium.engine;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * S9-E1: the tile threads. The server thread publishes a {@link Job}, runs tiles itself, and returns when every tile of
 * the job has finished. A {@link ColourJob} hands out one colour's tiles from a shared index (work stealing); a
 * {@link DagJob} (S9-E3) hands out a tile once its lower-colour neighbours are done; a {@link ForJob} (S9-E9) hands out
 * blocks of a parallel for outside any tile. Between jobs the threads spin briefly, then park until the next job is
 * published. S9-E11: how long they spin and park is a {@link Waiting} (from {@link EngineConfig}; D29's by default).
 *
 * <p>S9-E3 services: a running tile that needs what only the server thread may do with nothing else running (load a
 * chunk or a POI column, write outside its claim) pauses with {@link Job#service}; once no other tile is running
 * (the others are finished or paused) and none is starting, the server thread runs every pending service in order,
 * then the paused tiles go on. Vanilla does the same work at the same point of the same tile; only the other tiles'
 * progress around it differs.
 */
final class EngineWorkerPool {
    /**
     * S9-E11: how the threads wait. {@code spins} rounds of Thread.onSpinWait before a waiting thread parks; a tile thread
     * without a job parks {@code idleParkNanos} at a time; with {@code dagWake} a tile thread with no ready tile in a dag
     * job parks up to {@code dagParkNanos} and is unparked by a finished tile that readies another or by the job's end.
     */
    record Waiting(int spins, long idleParkNanos, boolean dagWake, long dagParkNanos, long idleParkMaxNanos) {
        /** D29: 8192 spins, 1 ms idle parks, dag waits poll every 20 us. */
        static final Waiting D29 = new Waiting(8_192, 1_000_000L, false, 20_000L);

        /** S9-E11's four parts: idle parks stay at idleParkNanos (no backoff). */
        Waiting(int spins, long idleParkNanos, boolean dagWake, long dagParkNanos) {
            this(spins, idleParkNanos, dagWake, dagParkNanos, idleParkNanos);
        }

        static Waiting configured() {
            return new Waiting(EngineConfig.SPINS, EngineConfig.IDLE_PARK_US * 1_000L, EngineConfig.DAG_WAKE,
                EngineConfig.DAG_PARK_US * 1_000L, EngineConfig.IDLE_PARK_MAX_US * 1_000L);
        }

        /**
         * S9-E20 (D44): the next idle park of a tile thread that found no job after parking {@code current}: double it, up to
         * {@code max}; a maximum at or below the base means no backoff. A published job unparks every tile thread, so the
         * park length never delays a job.
         */
        static long nextIdlePark(long current, long base, long max) {
            if (max <= base) return base;
            return Math.min(max, Math.max(base, current) * 2);
        }
    }

    /** Runs a service's work on the server thread (TileEngine leaves the running tile around it). */
    interface ServiceRunner {
        Object run(Supplier<?> work);
    }

    /** One service request: work for the server thread and the paused tile thread waiting for its result. */
    static final class Service {
        final Supplier<?> work;
        final Thread requester = Thread.currentThread();
        Object result;
        Throwable failure;
        volatile boolean done;

        Service(Supplier<?> work) { this.work = work; }
    }

    /** One parallel run of tiles. */
    abstract static class Job {
        final Runner runner;
        final Thread coordinator = Thread.currentThread();
        final AtomicInteger remaining;
        /** The first tile failure; later ones are attached to it as suppressed. */
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        /** Tiles running now (paused ones excluded), or -1 while the server thread runs services. */
        final AtomicInteger active = new AtomicInteger();
        final ConcurrentLinkedQueue<Service> services = new ConcurrentLinkedQueue<>();
        volatile boolean serviceWanted;
        /** Set by {@link EngineWorkerPool#run} before the job is published. */
        ServiceRunner serviceRunner = Supplier::get;
        /** S9-E11: set by {@link EngineWorkerPool#run}: the pool's waiting and the tile threads a release may unpark. */
        Waiting waiting = Waiting.D29;
        Thread[] wakeTargets = new Thread[0];
        /** S9-E11: tile threads parked (or about to park) for a ready tile of this job. */
        final AtomicInteger sleepers = new AtomicInteger();

        Job(int count, Runner runner) {
            this.runner = runner;
            this.remaining = new AtomicInteger(count);
        }

        /** Runs tiles on the calling thread until this thread can take no more of them. */
        abstract void work(TileContext context);

        final void fail(Throwable cause) {
            if (!failure.compareAndSet(null, cause)) {
                Throwable first = failure.get();
                if (first != cause) first.addSuppressed(cause);
            }
        }

        /** Runs one tile (skipped once a tile has failed) and counts it done. */
        final void runOne(TileWork tile, TileContext context) {
            beginTile();
            try {
                if (failure.get() == null) runner.run(tile, context);
            } catch (Throwable cause) {
                fail(cause);
            } finally {
                active.decrementAndGet();
                release(tile);
                if (remaining.decrementAndGet() == 0) {
                    LockSupport.unpark(coordinator);
                    if (waiting.dagWake()) wakeSleepers();
                }
            }
        }

        /** S9-E11: unparks the tile threads if any is parked for this job (a parked one re-checks and goes on or parks again). */
        final void wakeSleepers() {
            if (sleepers.get() > 0) {
                for (Thread target : wakeTargets) LockSupport.unpark(target);
            }
        }

        void release(TileWork tile) { }

        /** Waits until no service is wanted or running, then counts this thread's tile as running. */
        final void beginTile() {
            int idle = 0;
            while (true) {
                if (!serviceWanted) {
                    int now = active.get();
                    if (now >= 0 && active.compareAndSet(now, now + 1)) {
                        if (!serviceWanted) return;
                        active.decrementAndGet();      // a service was asked for meanwhile: let it run first
                    }
                } else if (Thread.currentThread() == coordinator) {
                    serve();
                    continue;
                }
                idle = pause(idle);
            }
        }

        /**
         * A running tile's request: pauses the tile, has the server thread run {@code work} once nothing else runs, and
         * returns its result (rethrowing its failure) after the tile has resumed.
         */
        @SuppressWarnings("unchecked")
        final <T> T service(Supplier<T> work) {
            Service request = new Service(work);
            services.add(request);
            serviceWanted = true;
            active.decrementAndGet();                  // this tile pauses
            if (Thread.currentThread() == coordinator) {
                int idle = 0;
                while (!request.done) {
                    serve();
                    if (!request.done) idle = pause(idle);
                }
            } else {
                LockSupport.unpark(coordinator);
                int idle = 0;
                while (!request.done) idle = pause(idle);
            }
            beginTile();
            if (request.failure instanceof RuntimeException runtime) throw runtime;
            if (request.failure instanceof Error error) throw error;
            if (request.failure != null) throw new IllegalStateException("tile service failed", request.failure);
            return (T) request.result;
        }

        /** Server thread: runs the pending services if no tile is running; otherwise returns at once. */
        final void serve() {
            if (!serviceWanted || !active.compareAndSet(0, -1)) return;
            try {
                // no tile runs while active is -1, so none can ask for another service until this returns
                Service request;
                while ((request = services.poll()) != null) {
                    try {
                        request.result = serviceRunner.run(request.work);
                    } catch (Throwable cause) {
                        request.failure = cause;
                    }
                    request.done = true;
                    LockSupport.unpark(request.requester);
                }
                serviceWanted = false;
            } finally {
                active.set(0);
            }
        }

        private int pause(int idle) {
            if (idle < waiting.spins()) {
                Thread.onSpinWait();
                return idle + 1;
            }
            LockSupport.parkNanos(20_000L);
            return idle;
        }
    }

    /** One colour's tiles: any order, any thread. */
    static final class ColourJob extends Job {
        final TileWork[] tiles;
        final int count;
        final AtomicInteger next = new AtomicInteger();

        ColourJob(TileWork[] tiles, int count, Runner runner) {
            super(count, runner);
            this.tiles = tiles;
            this.count = count;
        }

        @Override
        void work(TileContext context) {
            int index;
            while ((index = next.getAndIncrement()) < count) runOne(tiles[index], context);
        }
    }

    /**
     * S9-E3: a whole phase's tiles. A tile is ready once every neighbour of a lower colour is done (its pending count is
     * zero), so two tiles whose claims touch never run at the same time and each sees its lower-colour neighbours
     * finished: the same isolation as colour by colour, without a barrier between colours.
     */
    static final class DagJob extends Job {
        final ConcurrentLinkedQueue<TileWork> ready = new ConcurrentLinkedQueue<>();

        /** {@code roots} are the tiles without lower-colour neighbours, in dispatch order; every pending count is set. */
        DagJob(TileWork[] roots, int rootCount, int count, Runner runner) {
            super(count, runner);
            for (int i = 0; i < rootCount; i++) ready.add(roots[i]);
        }

        @Override
        void work(TileContext context) {
            int idle = 0;
            while (remaining.get() != 0) {
                TileWork tile = ready.poll();
                if (tile != null) {
                    idle = 0;
                    runOne(tile, context);
                } else {
                    boolean server = Thread.currentThread() == coordinator;
                    if (server) serve();
                    if (idle < waiting.spins()) {
                        idle++;
                        Thread.onSpinWait();
                    } else if (waiting.dagWake() && !server) {
                        // S9-E11: announce, re-check, park; a release that readies a tile (or the job's end) unparks us
                        sleepers.incrementAndGet();
                        try {
                            if (ready.isEmpty() && remaining.get() != 0) LockSupport.parkNanos(this, waiting.dagParkNanos());
                        } finally {
                            sleepers.decrementAndGet();
                        }
                    } else {
                        LockSupport.parkNanos(this, 20_000L);
                    }
                }
            }
        }

        @Override
        void release(TileWork tile) {
            TileWork[] next = tile.successors;
            boolean readied = false;
            for (int i = 0, n = tile.successorCount; i < n; i++) {
                TileWork successor = next[i];
                if (successor.pending.decrementAndGet() == 0) {
                    ready.add(successor);
                    readied = true;
                }
            }
            if (readied && waiting.dagWake()) wakeSleepers();
        }
    }

    /** One item of a {@link ForJob}. */
    interface ForBody {
        void run(int index);
    }

    /**
     * S9-E9: a parallel for over items 0..count-1, taken in blocks of {@code block} by any thread, outside any tile (the
     * running thread's context has no tile, so TileEngine.current() is null in the body: no tile state, no services). With
     * {@code capture} every thread holds the packets it sends while it runs the job's items in its context's outbox
     * ({@link EngineOutbox}); the caller delivers them after the job.
     */
    static final class ForJob extends Job {
        final int count;
        final int block;
        final ForBody body;
        final boolean capture;
        final AtomicInteger next = new AtomicInteger();
        /** The summed run time of the job's blocks, on every thread. */
        final AtomicLong workNanos = new AtomicLong();

        ForJob(int count, int block, ForBody body, boolean capture) {
            super(count, null);
            this.count = count;
            this.block = Math.max(1, block);
            this.body = body;
            this.capture = capture;
        }

        @Override
        void work(TileContext context) {
            if (next.get() >= count) return;
            if (capture) context.capturing = true;
            try {
                int start;
                while ((start = next.getAndAdd(block)) < count) {
                    int end = Math.min(count, start + block);
                    long begin = System.nanoTime();
                    try {
                        if (failure.get() == null) {
                            for (int i = start; i < end; i++) body.run(i);
                        }
                    } catch (Throwable cause) {
                        fail(cause);
                    } finally {
                        workNanos.addAndGet(System.nanoTime() - begin);
                        if (remaining.addAndGet(start - end) == 0) LockSupport.unpark(coordinator);
                    }
                }
            } finally {
                if (capture) context.capturing = false;
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
            long park = waiting.idleParkNanos();
            while (!stopping) {
                Job published = job;
                if (published != null && published != seen) {
                    seen = published;
                    published.work(context);
                    idle = 0;
                    park = waiting.idleParkNanos();
                } else if (idle < waiting.spins()) {
                    idle++;
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(this, park);
                    IDLE_PARKS.increment();
                    park = Waiting.nextIdlePark(park, waiting.idleParkNanos(), waiting.idleParkMaxNanos());
                }
            }
        }
    }

    /** S9-E20: idle parks of every pool's tile threads (diagnostics). */
    private static final LongAdder IDLE_PARKS = new LongAdder();

    private final Worker[] workers;
    private final ServiceRunner serviceRunner;
    private final Waiting waiting;
    private volatile Job job;
    private volatile boolean stopping;

    EngineWorkerPool(int threads) {
        this(threads, Supplier::get, Waiting.D29);
    }

    EngineWorkerPool(int threads, ServiceRunner serviceRunner) {
        this(threads, serviceRunner, Waiting.configured());
    }

    EngineWorkerPool(int threads, ServiceRunner serviceRunner, Waiting waiting) {
        this.serviceRunner = serviceRunner;
        this.waiting = waiting;
        workers = new Worker[threads];
        for (int i = 0; i < threads; i++) workers[i] = new Worker(i + 1);
        for (Worker worker : workers) worker.start();
    }

    int threads() { return workers.length; }

    /** S9-E20: idle parks of the tile threads so far (all pools of this JVM). */
    long idleParks() { return IDLE_PARKS.sum(); }

    static long idleParksTotal() { return IDLE_PARKS.sum(); }

    /** The tile threads' contexts, in thread order (S9-E9: their outboxes are delivered after a capturing job). */
    TileContext[] contexts() {
        TileContext[] out = new TileContext[workers.length];
        for (int i = 0; i < workers.length; i++) out[i] = workers[i].context;
        return out;
    }

    /** The job being run, or null; a tile thread asks for services through it. */
    Job current() { return job; }

    /** Server thread: runs every tile of the job (here and on the tile threads); rethrows the first tile failure. */
    void run(Job published, TileContext own) {
        published.serviceRunner = serviceRunner;
        published.waiting = waiting;
        published.wakeTargets = workers;
        job = published;
        for (Worker worker : workers) LockSupport.unpark(worker);
        try {
            published.work(own);
            int spins = 0;
            while (published.remaining.get() != 0) {
                published.serve();
                if (spins < waiting.spins()) {
                    spins++;
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(this, 20_000L);
                }
            }
        } finally {
            job = null;
        }
        Throwable failure = published.failure.get();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("tile failed", failure);
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
