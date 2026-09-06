package dev.phoenium.chunk;

/** Observer attachment is independent of visible-holder publication and never conveys authority. */
public final class FullInputNotifications {
    private volatile FullPublishedInputs publicationObserver;
    public void publicationObserver(FullPublishedInputs observer) { publicationObserver = observer; }
    private final java.util.concurrent.CopyOnWriteArraySet<Runnable> listeners = new java.util.concurrent.CopyOnWriteArraySet<>();
    public AutoCloseable subscribe(Runnable listener) { listeners.add(listener); return () -> listeners.remove(listener); }
    public void published() {
        FullPublishedInputs observer = publicationObserver;
        if (observer != null) observer.visiblePublished();
        listeners.forEach(Runnable::run);
    }
    public int retainedListeners() { return listeners.size(); }
}
