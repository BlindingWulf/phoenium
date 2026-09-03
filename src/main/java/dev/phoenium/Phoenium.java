package dev.phoenium;

import net.fabricmc.api.DedicatedServerModInitializer;

/** Stage 1 intentionally establishes no live-server scheduling hook. */
public final class Phoenium implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        System.getLogger("Phoenium").log(System.Logger.Level.INFO,
            "Phoenium Stage 1 isolated serial core loaded; live tick integration is disabled by design.");
    }
}
