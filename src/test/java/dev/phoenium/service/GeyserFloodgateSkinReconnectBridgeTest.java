package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class GeyserFloodgateSkinReconnectBridgeTest {
    @Test void queuedReconnectRunsWhileProviderIsOpenAndStopsAfterClose() {
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicInteger reconnects = new AtomicInteger();
        Runnable guarded = GeyserFloodgateSkinReconnectBridge.guard(closed::get, reconnects::incrementAndGet);

        guarded.run();
        assertEquals(1, reconnects.get());

        closed.set(true);
        guarded.run();
        assertEquals(1, reconnects.get());
    }

    @Test void productionMixinRegistersTheExactProviderAdapter() throws Exception {
        try (InputStream input = Objects.requireNonNull(
                getClass().getResourceAsStream("/phoenium.mixins.json"))) {
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("GeyserFloodgateSkinUploaderMixin"));
        }
    }
}
