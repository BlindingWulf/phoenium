package dev.phoenium.service;

import dev.phoenium.ownership.OwnershipContext;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Native poll identity and readiness observation for one serial natural-spawn query.
 * Completion callbacks only publish metadata. They never enter gameplay or finish a poll.
 * The native managedBlock implementation still owns readiness checks and polling.
 */
public final class ManagedBlockPollTurnFence {
    public interface Loop { ManagedBlockPollTurnFence phoenium$naturalSpawnFence(); }
    public enum State {
        WAITING_DEPENDENCY, DEPENDENCY_COMPLETE_OUTSIDE_POLL, DEPENDENCY_COMPLETE_IN_POLL,
        RETURN_FENCE_READY, POLL_RETURNED_READY, RESUME_CLAIMED, RESUMING, SETTLED,
        FAILED, CANCELLED, STALE, SHUTDOWN
    }
    private static final ThreadLocal<Reservation> CURRENT = new dev.phoenium.core.FastThreadLocal<>();
    private final Thread thread;
    private long nextPoll;
    private Reservation reserved;

    public ManagedBlockPollTurnFence(Thread thread) { this.thread = Objects.requireNonNull(thread); }

    /** Nested synchronous service drains inherit this identity without allocating a turn. */
    public static long currentPollId() {
        Reservation current = CURRENT.get();
        return current == null ? 0 : current.activePoll();
    }

    /** True while this thread is inside any native return-fence wait, including its pumped tasks. */
    public static boolean reservationActive() { return CURRENT.get() != null; }

    public Reservation reserve(long captureOrdinal, long queryGeneration, CompletionStage<?> dependency,
                               BooleanSupplier valid, Runnable requireOwnerFree) {
        requireThread();
        Objects.requireNonNull(dependency); Objects.requireNonNull(requireOwnerFree);
        if (reserved != null || CURRENT.get() != null)
            throw new IllegalStateException("overlapping natural-spawn query reservation");
        Reservation result = new Reservation(captureOrdinal, queryGeneration, valid, requireOwnerFree);
        reserved = result;
        dependency.whenComplete((value, failure) -> result.completed(failure));
        return result;
    }

    /** Wraps the entire native poll, including the HEAD service pumps and their drains. */
    public boolean poll(BooleanSupplier original) {
        Reservation target = CURRENT.get();
        if (target == null || target != reserved) return original.getAsBoolean();
        requireThread();
        long id;
        boolean nested;
        synchronized (target) { nested = target.poll != 0; }
        if (nested) return original.getAsBoolean();
        synchronized (target) {
            // Only the native false-readiness observation can authorize a new turn.
            if (!target.pollAdmitted) throw new IllegalStateException("poll without false native readiness observation");
            target.pollAdmitted = false;
            target.poll = id = nextPoll = Math.incrementExact(nextPoll);
            target.incompletePoll = id;
        }
        Throwable primary = null;
        try {
            boolean result = original.getAsBoolean();
            target.requireOwnerFree.run();
            target.prepareIfActive();
            return result;
        }
        catch (RuntimeException | Error failure) { primary = failure; target.fail(failure); throw failure; }
        finally {
            try { target.requireOwnerFree.run(); }
            catch (RuntimeException | Error boundaryFailure) {
                if (primary == null) { target.fail(boundaryFailure); throw boundaryFailure; }
                if (primary != boundaryFailure) primary.addSuppressed(boundaryFailure);
            } finally {
                synchronized (target) {
                    target.poll = 0;
                    target.returnedPoll = id;
                    // Native managedBlock may waitForTasks after a false poll; only
                    // its next supplier call is the readiness observation.
                }
            }
        }
    }

    private void requireThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("natural-spawn fence left native loop thread");
    }

    public final class Reservation implements AutoCloseable {
        private final long captureOrdinal, queryGeneration;
        private BooleanSupplier valid;
        private Runnable requireOwnerFree;
        private State state = State.WAITING_DEPENDENCY;
        private Throwable failure;
        private long poll, incompletePoll, completedPoll, returnedPoll;
        private boolean pollAdmitted;
        private Runnable prepare = () -> { };
        private BooleanSupplier shuttingDown = () -> false;

        private Reservation(long captureOrdinal, long queryGeneration, BooleanSupplier valid, Runnable ownerFree) {
            if (captureOrdinal <= 0 || queryGeneration <= 0) throw new IllegalArgumentException("invalid natural-spawn identity");
            this.captureOrdinal = captureOrdinal; this.queryGeneration = queryGeneration;
            this.valid = Objects.requireNonNull(valid);
            this.requireOwnerFree = () -> {
                if (OwnershipContext.current().isPresent()) throw new IllegalStateException("natural-spawn fence retained owner context");
                Objects.requireNonNull(ownerFree).run();
            };
        }
        private synchronized void completed(Throwable cause) {
            if (state != State.WAITING_DEPENDENCY) return;
            if (cause != null) { failure = cause; state = State.FAILED; return; }
            completedPoll = poll;
            state = poll == 0 ? State.DEPENDENCY_COMPLETE_OUTSIDE_POLL : State.DEPENDENCY_COMPLETE_IN_POLL;
        }
        public synchronized State state() { return state; }
        public synchronized Throwable failure() { return failure; }
        public synchronized long activePoll() { return poll; }
        public synchronized long incompletePoll() { return incompletePoll; }
        public synchronized long completedPoll() { return completedPoll; }
        public synchronized long returnedPoll() { return returnedPoll; }
        public long captureOrdinal() { return captureOrdinal; }
        public long queryGeneration() { return queryGeneration; }
        /** Only dependency preparation, without polling or ordinary gameplay admission. */
        public Reservation prepareAtObservation(Runnable preparation) {
            requireThread(); this.prepare = Objects.requireNonNull(preparation); return this;
        }
        public Reservation shutdownWhen(BooleanSupplier shuttingDown) {
            requireThread(); this.shuttingDown = Objects.requireNonNull(shuttingDown); return this;
        }

        /** Runs the actual event loop, then claims the exact continuation before returning
         * control to any outer service arbitration. Resume must reacquire its current owner. */
        public void awaitAndResume(Consumer<BooleanSupplier> nativeManagedBlock, Runnable resume) {
            requireThread(); requireOwnerFree.run();
            if (reserved != this || CURRENT.get() != null) throw new IllegalStateException("inactive natural-spawn reservation");
            CURRENT.set(this);
            try {
                nativeManagedBlock.accept(this::observeReadiness);
                requireOwnerFree.run();
                synchronized (this) {
                    if (state == State.FAILED) rethrow(failure);
                    if (terminal()) return;
                }
                boolean stopping = shuttingDown.getAsBoolean();
                boolean current = valid.getAsBoolean();
                synchronized (this) {
                    if (state == State.FAILED) rethrow(failure);
                    if (terminal()) return;
                    if (stopping) { state = State.SHUTDOWN; return; }
                    if (state != State.RETURN_FENCE_READY && state != State.POLL_RETURNED_READY)
                        throw new IllegalStateException("native wait returned without readiness observation");
                    if (!current) { state = State.STALE; return; }
                    state = State.RESUME_CLAIMED;
                }
                resume.run();
                requireOwnerFree.run();
                synchronized (this) {
                    if (terminal()) return;
                    if (state != State.RESUMING) throw new IllegalStateException("resume did not claim current owner");
                    state = State.SETTLED;
                }
            } catch (RuntimeException | Error cause) { fail(cause); throw cause; }
            finally { CURRENT.set(null); close(); }
        }
        public void requireResumeClaim() {
            requireThread(); requireOwnerFree.run();
            synchronized (this) {
                if (CURRENT.get() != this || reserved != this || poll != 0 || state != State.RESUME_CLAIMED)
                    throw new IllegalStateException("natural-spawn resume lacks its native return reservation");
            }
        }
        public void beginResuming() {
            requireThread();
            boolean current = valid.getAsBoolean();
            synchronized (this) {
                if (CURRENT.get() != this || state != State.RESUME_CLAIMED || OwnershipContext.current().isEmpty())
                    throw new IllegalStateException("natural-spawn suffix lacks its current owner claim");
                if (!current) { state = State.STALE; return; }
                state = State.RESUMING;
            }
        }
        private boolean observeReadiness() {
            requireThread(); requireOwnerFree.run();
            prepareIfActive();
            synchronized (this) { if (terminal()) return true; }
            boolean stopping = shuttingDown.getAsBoolean();
            boolean current = valid.getAsBoolean();
            synchronized (this) {
                if (poll != 0) throw new IllegalStateException("readiness observed inside admitted poll");
                if (terminal()) return true;
                if (stopping) { state = State.SHUTDOWN; return true; }
                if (!current) { state = State.STALE; return true; }
                if (state == State.DEPENDENCY_COMPLETE_IN_POLL) {
                    if (completedPoll != returnedPoll) throw new IllegalStateException("unmatched poll return");
                    state = State.POLL_RETURNED_READY; return true;
                }
                if (state == State.DEPENDENCY_COMPLETE_OUTSIDE_POLL) {
                    state = State.RETURN_FENCE_READY; return true;
                }
                if (state != State.WAITING_DEPENDENCY) throw new IllegalStateException("duplicate readiness after claim");
                pollAdmitted = true; return false;
            }
        }
        private void prepareIfActive() {
            synchronized (this) { if (terminal()) return; }
            prepare.run();
        }
        public synchronized void retire(State reason) {
            if (reason != State.STALE && reason != State.CANCELLED && reason != State.SHUTDOWN)
                throw new IllegalArgumentException("not a retirement reason");
            if (!terminal() && state != State.RESUMING) state = reason;
        }
        private synchronized void fail(Throwable cause) {
            if (failure == null) failure = cause;
            if (!terminal()) state = State.FAILED;
        }
        private boolean terminal() {
            return state == State.FAILED || state == State.CANCELLED || state == State.STALE
                || state == State.SHUTDOWN || state == State.SETTLED;
        }
        @Override public void close() {
            requireThread();
            synchronized (this) {
                if (poll != 0) throw new IllegalStateException("reservation closed inside admitted poll");
                if (!terminal()) state = State.CANCELLED;
                valid = () -> false;
                shuttingDown = () -> false;
                prepare = () -> { };
                requireOwnerFree = () -> { };
            }
            if (reserved == this) reserved = null;
        }
    }
    private static void rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error error) throw error;
        throw new IllegalStateException("natural-spawn dependency failed", cause);
    }
}
