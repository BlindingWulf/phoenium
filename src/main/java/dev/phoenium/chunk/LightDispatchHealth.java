package dev.phoenium.chunk;

/** A consecutive executor's dispatch failure is service health, not holder correlation. */
public interface LightDispatchHealth {
    Throwable phoenium$dispatchFailure();
}
