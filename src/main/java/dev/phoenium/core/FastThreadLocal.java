package dev.phoenium.core;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * S9-D9: a {@link ThreadLocal} whose value on the server thread, and on Phoenium's gameplay workers, lives in a
 * plain per-thread array slot instead of the thread's {@code ThreadLocalMap}. Phoenium's scopes are read on nearly
 * every owner turn, entity and chunk query; with a hundred-odd thread locals on the server thread, the map's linear
 * probing ({@code getEntryAfterMiss}) cost about 5% of the server thread in Stage 9 profiles.
 *
 * <p>Semantics are those of {@code ThreadLocal}: a value is visible only to the thread that set it, an unset value is
 * {@link #initialValue()}, and {@code set(null)} differs from {@code remove()} only for an initial-value supplier.
 * A slotted thread reads and writes only its own slots; every other thread uses the inherited map, unchanged.
 */
public class FastThreadLocal<T> extends ThreadLocal<T> {
    private static final Object NULL = new Object();
    private static final AtomicInteger NEXT = new AtomicInteger();

    /** One thread's slots. Only that thread reads or writes them. */
    private static final class Slots {
        final Thread thread;
        Object[] values;
        Slots(Thread thread) { this.thread = thread; this.values = new Object[Math.max(64, NEXT.get() + 16)]; }
        Object[] values(int index) {
            Object[] current = values;
            if (index >= current.length) values = current = Arrays.copyOf(current, Math.max(index + 1, current.length * 2));
            return current;
        }
    }

    /** A thread created with its own slots (Phoenium's gameplay workers). */
    public static final class Carrier extends Thread {
        private final Slots slots = new Slots(this);
        public Carrier(Runnable body, String name) { super(body, name); }
    }

    /**
     * The bound server thread's slots. Written only by {@link #bindServerThread()} on the thread it names, so that
     * thread always sees its own binding; any other thread that reads it, current or stale, finds a different thread
     * and uses the map.
     */
    private static Slots server;

    private final int index = NEXT.getAndIncrement();
    private final Supplier<? extends T> initial;

    public FastThreadLocal() { this.initial = null; }
    private FastThreadLocal(Supplier<? extends T> initial) { this.initial = Objects.requireNonNull(initial, "initial"); }

    public static <S> FastThreadLocal<S> withInitial(Supplier<? extends S> supplier) { return new FastThreadLocal<>(supplier); }

    /**
     * Gives the calling thread (the server thread, at the start of {@code runServer}) fresh, empty slots. A thread
     * that bound earlier and is still alive keeps its slots, and the caller then uses the map: a slotted thread never
     * switches mid-scope. Binding the already bound thread again keeps its slots.
     */
    public static boolean bindServerThread() {
        Thread thread = Thread.currentThread();
        Slots previous = server;
        if (previous != null && previous.thread == thread) return true;
        if (previous != null && previous.thread.isAlive()) return false;
        server = new Slots(thread);
        return true;
    }

    /** S9-D15 diagnostics: the calling thread, whether it is slotted, the bound server thread and this class's
     * identity and loader (a status line on the server thread shows whether its binding holds). */
    public static String describe() {
        Thread thread = Thread.currentThread();
        Slots bound = server;
        return "thread=" + thread.getName() + "#" + thread.threadId() + " slotted=" + slotted()
            + " bound=" + (bound == null ? "none" : bound.thread.getName() + "#" + bound.thread.threadId()
                + (bound.thread.isAlive() ? "" : "(dead)"))
            + " class=" + Integer.toHexString(System.identityHashCode(FastThreadLocal.class))
            + " loader=" + FastThreadLocal.class.getClassLoader();
    }

    /** Whether the calling thread reads and writes slots rather than its map (diagnostics and tests). */
    public static boolean slotted() { return slots() != null; }

    private static Slots slots() {
        Thread thread = Thread.currentThread();
        Slots bound = server;
        if (bound != null && bound.thread == thread) return bound;
        return thread instanceof Carrier carrier ? carrier.slots : null;
    }

    @Override protected T initialValue() { return initial == null ? null : initial.get(); }

    @Override @SuppressWarnings("unchecked")
    public T get() {
        Slots slots = slots();
        if (slots == null) return super.get();
        Object[] values = slots.values;
        Object value = index < values.length ? values[index] : null;
        if (value == null) {
            T first = initialValue();
            slots.values(index)[index] = first == null ? NULL : first;
            return first;
        }
        return value == NULL ? null : (T) value;
    }

    @Override public void set(T value) {
        Slots slots = slots();
        if (slots == null) { super.set(value); return; }
        slots.values(index)[index] = value == null ? NULL : value;
    }

    @Override public void remove() {
        Slots slots = slots();
        if (slots == null) { super.remove(); return; }
        Object[] values = slots.values;
        if (index < values.length) values[index] = null;
    }
}
