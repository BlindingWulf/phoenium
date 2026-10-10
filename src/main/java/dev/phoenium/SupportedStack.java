package dev.phoenium;

import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * S9-E16 (S8-F1): Phoenium is certified only with the patched C2ME (the "phoenium-settlement2" build). Without it, the
 * default mode's portal transfer fails closed at the first mob portal ("portal destination lacks exact resident
 * publication for native FULL result", Stage 8 layer L0), i.e. the server runs until a mob takes a portal and then
 * crashes. This guard refuses to start the dedicated server instead, with a message naming what is missing, before any
 * world loads. {@code -Dphoenium.allowUnsupportedStack=true} lets a test start an unsupported stack anyway (logged).
 */
public final class SupportedStack {
    static final String C2ME_ID = "c2me";
    static final String C2ME_BUILD = "phoenium-settlement2";
    public static final String OVERRIDE = "phoenium.allowUnsupportedStack";

    private SupportedStack() { }

    /** Why the stack is not supported, or null: {@code c2meVersion} is the loaded C2ME version, null when absent. */
    static String problem(String c2meVersion) {
        if (c2meVersion == null) return "C2ME (mod id 'c2me') is not installed";
        if (!c2meVersion.contains(C2ME_BUILD)) return "C2ME " + c2meVersion + " is not the patched '" + C2ME_BUILD + "' build";
        return null;
    }

    /** Dedicated server start (from {@link Phoenium#onInitializeServer}): throws when the stack is unsupported. */
    static void require(Logger logger) {
        Optional<String> version = FabricLoader.getInstance().getModContainer(C2ME_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString());
        String problem = problem(version.orElse(null));
        if (problem == null) {
            logger.info("PHOENIUM_STACK supported: c2me {}", version.get());
            return;
        }
        if (Boolean.getBoolean(OVERRIDE)) {
            logger.warn("PHOENIUM_STACK unsupported, started only because -D{}=true (tests only): {}", OVERRIDE, problem);
            return;
        }
        throw new IllegalStateException("Phoenium needs the patched C2ME ('" + C2ME_BUILD + "' build) that it is certified "
            + "with: " + problem + ". Without it the first mob portal crashes the server (Stage 8 S8-F1). Install the "
            + "certified stack (C2ME 0.4.2-alpha.0.43-" + C2ME_BUILD + "), or start with -D" + OVERRIDE + "=true for tests only.");
    }
}
