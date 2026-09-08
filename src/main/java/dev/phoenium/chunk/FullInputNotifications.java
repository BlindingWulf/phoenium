package dev.phoenium.chunk;

/** Observer attachment is independent of visible-holder publication and never conveys authority. */
public final class FullInputNotifications {
    private volatile FullPublishedInputs publicationObserver;
    public void publicationObserver(FullPublishedInputs observer) { publicationObserver = observer; }
    private final java.util.concurrent.CopyOnWriteArraySet<Runnable> listeners = new java.util.concurrent.CopyOnWriteArraySet<>();
    public AutoCloseable subscribe(Runnable listener) { listeners.add(listener); return () -> listeners.remove(listener); }
    public void published() {
        var failures = new Failures();
        FullPublishedInputs observer = publicationObserver;
        if (observer != null) failures.run(observer::visiblePublished);
        for (Runnable listener : listeners) failures.run(listener::run);
        failures.rethrow();
    }
    public int retainedListeners() { return listeners.size(); }

    /** A committed publication or acknowledgement does not cancel its independent siblings. */
    public static final class Failures {
        @FunctionalInterface public interface Action { void run() throws Exception; }
        private Throwable first;
        public void run(Action action) {
            try { action.run(); }
            catch (Throwable failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        public void rethrow() {
            if (first instanceof RuntimeException runtime) throw runtime;
            if (first instanceof Error error) throw error;
            if (first != null) throw new ChunkProtocolException("FULL input acknowledgement failed", first);
        }
    }
}
