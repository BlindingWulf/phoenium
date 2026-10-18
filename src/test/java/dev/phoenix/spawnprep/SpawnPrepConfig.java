package dev.phoenix.spawnprep;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * {@code config/phoenix-spawnprep.properties}. A bad value never stops the server: it falls back or is clamped, and the
 * warning is logged.
 */
public record SpawnPrepConfig(boolean enabled, int radiusBlocks, int maxInFlight, boolean existingWorlds, List<String> warnings) {
    /** Provisional default (owner 2026-10-17): 256 blocks until the Phone A radius study confirms it protects the join. */
    public static final int DEFAULT_RADIUS = 256, MAX_RADIUS = 4096, DEFAULT_IN_FLIGHT = 16, MAX_IN_FLIGHT = 256;

    public static SpawnPrepConfig from(Properties p) {
        List<String> warnings = new ArrayList<>();
        boolean enabled = bool(p, "enabled", true, warnings);
        int radius = integer(p, "radiusBlocks", DEFAULT_RADIUS, 0, MAX_RADIUS, warnings);
        int inFlight = integer(p, "maxInFlight", DEFAULT_IN_FLIGHT, 1, MAX_IN_FLIGHT, warnings);
        boolean existing = bool(p, "existingWorlds", false, warnings);
        return new SpawnPrepConfig(enabled, radius, inFlight, existing, List.copyOf(warnings));
    }

    public static String defaultFileText() {
        return """
            # Phoenix spawn preparation: generates the terrain around spawn before the server reports "Done".
            # Only missing chunks are generated; finished chunks are never loaded or rewritten.
            enabled=true
            # Square radius around spawn, in blocks (0-4096). 256 is provisional; 1280 was the first tested size.
            radiusBlocks=256
            # Chunks requested at once (1-256). Lower uses less memory, higher finishes sooner.
            maxInFlight=16
            # Also prepare worlds that existed before this mod was added (only their missing chunks).
            existingWorlds=false
            """;
    }

    private static boolean bool(Properties p, String key, boolean def, List<String> warnings) {
        String v = p.getProperty(key);
        if (v == null) return def;
        v = v.strip();
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        warnings.add(key + "=" + v + " is not true/false; using " + def);
        return def;
    }

    private static int integer(Properties p, String key, int def, int min, int max, List<String> warnings) {
        String v = p.getProperty(key);
        if (v == null) return def;
        int n;
        try {
            n = Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            warnings.add(key + "=" + v + " is not a whole number; using " + def);
            return def;
        }
        if (n < min || n > max) {
            int clamped = Math.max(min, Math.min(max, n));
            warnings.add(key + "=" + n + " is outside " + min + "-" + max + "; using " + clamped);
            return clamped;
        }
        return n;
    }
}
