package dev.phoenium;

import dev.phoenium.live.PhoeniumLiveRuntime;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-only Stage 2 lifecycle root. */
public final class Phoenium implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    private static final PhoeniumLiveRuntime LIVE = new PhoeniumLiveRuntime(LOGGER);

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(LIVE::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LIVE.stop());
        LOGGER.info("PHOENIUM_STAGE2 entrypoint registered once; live integration awaits server start.");
    }

    public static PhoeniumLiveRuntime liveRuntime() { return LIVE; }
}
