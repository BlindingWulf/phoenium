package dev.phoenium.topology;

/**
 * A frozen-authority escape detected before any foreign side effect. This is a hard failure
 * condition: the operation must not continue, clamp, replay, or fall back to a foreign owner.
 */
public class ClosureEscapeException extends IllegalStateException {
    public ClosureEscapeException(String message) { super(message); }
}
