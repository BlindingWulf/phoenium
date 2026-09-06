package dev.phoenium.chunk;

/** Original server chunk-cache publication thread, independent of the generation worker. */
public interface FullInputObservationThread {
    Thread phoenium$inputThread();
}
