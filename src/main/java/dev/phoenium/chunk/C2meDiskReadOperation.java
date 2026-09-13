package dev.phoenium.chunk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** One locked provider action, spanning private retries and the final publication.
 * The provider token CAS, not subscription disposal, arbitrates cancellation versus commit.
 * Synchronized sections contain only metadata and the token's callback-free complete/query.
 */
public final class C2meDiskReadOperation {
    public enum Terminal { APPLIED, CANCELLED, SUPERSEDED, SHUTDOWN, FAILED }

    public static final class Control extends CancellationException {
        private final Terminal terminal;
        Control(Terminal terminal) {
            super("C2ME disk action retired: " + terminal);
            this.terminal = terminal;
        }
        public Terminal terminal() { return terminal; }
    }

    private final Object token;
    private final Method cancelled;
    private final Method complete;
    private final Method cancel;
    private boolean committing;
    private Terminal terminal;
    private Throwable poison;
    private boolean settled;

    public C2meDiskReadOperation(Object token) {
        this.token = Objects.requireNonNull(token);
        if (!token.getClass().getName().equals("com.ishland.flowsched.scheduler.Cancellable"))
            throw new IllegalArgumentException("not the locked C2ME action token");
        try {
            cancelled = token.getClass().getMethod("isCancelled");
            complete = token.getClass().getMethod("complete");
            cancel = token.getClass().getMethod("cancel");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    /** A control is acknowledged only at a safe computation/admission checkpoint. */
    public synchronized void check() {
        if (poison != null) rethrow(poison);
        if (terminal != null) {
            if (terminal == Terminal.APPLIED) throw new IllegalStateException("late C2ME read after publication");
            throw new Control(terminal);
        }
        if (!committing && call(cancelled)) {
            terminal = Terminal.CANCELLED;
            throw new Control(terminal);
        }
    }

    /** Must run immediately before the first live effect. No effect runs under this monitor. */
    public synchronized void beginCommit() {
        check();
        if (committing) return;
        if (!call(complete)) {
            if (!call(cancelled)) throw new IllegalStateException("C2ME action token completed outside disk commit");
            terminal = Terminal.CANCELLED;
            throw new Control(terminal);
        }
        committing = true;
    }

    public void retire(Terminal reason) {
        if (reason != Terminal.SHUTDOWN && reason != Terminal.SUPERSEDED)
            throw new IllegalArgumentException("not an external retirement");
        synchronized (this) {
            if (terminal != null || poison != null || committing) return;
        }
        // cancel may invoke a provider callback: never call it under our monitor.
        // The native token CAS also prevents retirement from crossing a winning commit.
        boolean requestedHere = call(cancel);
        synchronized (this) {
            if (terminal == null && poison == null && !committing && call(cancelled))
                terminal = requestedHere ? reason : Terminal.CANCELLED;
        }
    }

    /** A failure after an attempted live effect must never trigger another parse/drain. */
    public synchronized void nonReplayableFailure(Throwable failure) {
        Objects.requireNonNull(failure);
        if (terminal == null && poison == null) {
            poison = failure;
            terminal = Terminal.FAILED;
        }
    }

    public synchronized boolean mustBypassRecovery(Throwable failure) {
        return failure instanceof Control || terminal != null || poison != null;
    }

    /** Called by the exact outer provider Completable event, after physical producer settlement. */
    public synchronized Terminal settle(Throwable failure) {
        if (settled) throw new IllegalStateException("duplicate C2ME disk provider terminal");
        settled = true;
        if (terminal == null) {
            if (failure != null) terminal = Terminal.FAILED;
            else {
                if (!committing) throw new IllegalStateException("C2ME published without commit arbitration");
                terminal = Terminal.APPLIED;
            }
        }
        if (failure == null && terminal != Terminal.APPLIED)
            throw new IllegalStateException("retired C2ME action returned success: " + terminal);
        return terminal;
    }

    public int tokenIdentity() { return System.identityHashCode(token); }

    public synchronized Terminal terminal() { return terminal; }

    private boolean call(Method method) {
        try { return Boolean.TRUE.equals(method.invoke(token)); }
        catch (InvocationTargetException failure) { rethrow(failure.getCause()); return false; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void rethrow(Throwable failure) {
        C2meDiskReadOperation.<RuntimeException>throwUnchecked(failure);
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T { throw (T) failure; }
}
