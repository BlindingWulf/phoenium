package dev.phoenium.service;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Keeps a provider-scheduled skin-uploader reconnect from reviving after provider close. */
public final class GeyserFloodgateSkinReconnectBridge {
    private GeyserFloodgateSkinReconnectBridge() { }

    public static Runnable guard(BooleanSupplier closed, Runnable reconnect) {
        Objects.requireNonNull(closed, "closed");
        Objects.requireNonNull(reconnect, "reconnect");
        return () -> {
            if (!closed.getAsBoolean()) reconnect.run();
        };
    }
}
