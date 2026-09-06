package dev.phoenium.chunk;

/** Observer attachment is independent of visible-holder publication and never conveys authority. */
public final class FullInputNotifications {
    private final java.util.concurrent.CopyOnWriteArraySet<Runnable> listeners = new java.util.concurrent.CopyOnWriteArraySet<>();
    public AutoCloseable subscribe(Runnable listener) { listeners.add(listener); return () -> listeners.remove(listener); }
    public void published() { listeners.forEach(Runnable::run); }
    public int retainedListeners() { return listeners.size(); }
}
