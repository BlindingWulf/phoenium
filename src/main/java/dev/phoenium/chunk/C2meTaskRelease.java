package dev.phoenium.chunk;

import java.util.concurrent.atomic.LongAdder;

/**
 * S9-M6 (MEM-6) switch and counter of {@code C2meScheduledTaskReleaseMixin}: C2ME worldgen tasks release their action
 * (and with it the captured proto chunk) once it has started. {@code -Dphoenium.c2me.releaseFinishedTasks=false} keeps
 * D35's behaviour. Applies in every mode wherever the C2ME scheduler is installed.
 */
public final class C2meTaskRelease {
    private C2meTaskRelease() { }

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("phoenium.c2me.releaseFinishedTasks"));
    private static final LongAdder RELEASED = new LongAdder();

    public static void released() { RELEASED.increment(); }

    public static long count() { return RELEASED.sum(); }

    public static String counters() { return "c2meTaskRelease=" + ENABLED + " c2meTasksReleased=" + RELEASED.sum(); }
}
