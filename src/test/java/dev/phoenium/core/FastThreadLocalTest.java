package dev.phoenium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** S9-D9: slotted threads keep ThreadLocal semantics; other threads use the map unchanged. */
class FastThreadLocalTest {
    private static void inThread(Thread thread) throws Exception {
        thread.start(); thread.join(10_000);
        assertFalse(thread.isAlive(), "test thread did not finish");
    }

    private static List<Object> exercise(FastThreadLocal<String> plain, FastThreadLocal<List<String>> supplied) {
        List<Object> seen = new ArrayList<>();
        seen.add(plain.get());
        plain.set("a"); seen.add(plain.get());
        plain.set(null); seen.add(plain.get());
        plain.set("b"); plain.remove(); seen.add(plain.get());
        List<String> first = supplied.get(); first.add("x");
        seen.add(supplied.get() == first);
        supplied.set(null); seen.add(supplied.get());
        supplied.remove(); seen.add(supplied.get() != first && supplied.get().isEmpty());
        return seen;
    }

    private static final List<Object> EXPECTED = java.util.Arrays.asList(null, "a", null, null, true, null, true);

    @Test void unslottedThreadsKeepThreadLocalSemantics() {
        assertEquals(EXPECTED, exercise(new FastThreadLocal<>(), FastThreadLocal.withInitial(ArrayList::new)));
    }

    @Test void carrierAndBoundThreadsKeepThreadLocalSemantics() throws Exception {
        var plain = new FastThreadLocal<String>(); var supplied = FastThreadLocal.<List<String>>withInitial(ArrayList::new);
        AtomicReference<Object> carrier = new AtomicReference<>(), bound = new AtomicReference<>();
        inThread(new FastThreadLocal.Carrier(() -> carrier.set(exercise(plain, supplied)), "fast-carrier"));
        inThread(new Thread(() -> {
            if (!FastThreadLocal.bindServerThread()) { bound.set("not bound"); return; }
            bound.set(exercise(plain, supplied));
        }, "fast-bound"));
        assertEquals(EXPECTED, carrier.get());
        assertEquals(EXPECTED, bound.get());
    }

    @Test void valuesStayWithTheThreadThatSetThem() throws Exception {
        var local = new FastThreadLocal<String>();
        local.set("test thread");
        AtomicReference<String> seen = new AtomicReference<>("unset");
        inThread(new FastThreadLocal.Carrier(() -> { seen.set(local.get()); local.set("carrier"); }, "fast-isolation"));
        assertNull(seen.get());
        assertEquals("test thread", local.get());
        local.remove();
    }

    @Test void aLiveBoundThreadKeepsItsSlots() throws Exception {
        var local = new FastThreadLocal<String>();
        var release = new java.util.concurrent.CountDownLatch(1);
        var ready = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> first = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            assertTrue(FastThreadLocal.bindServerThread());
            local.set("first");
            ready.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            first.set(local.get());
        }, "fast-holder");
        holder.start();
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
        AtomicReference<Boolean> second = new AtomicReference<>();
        inThread(new Thread(() -> second.set(FastThreadLocal.bindServerThread()), "fast-second"));
        release.countDown(); holder.join(10_000);
        assertFalse(second.get(), "a second thread cannot take a live thread's binding");
        assertEquals("first", first.get());
    }
}
