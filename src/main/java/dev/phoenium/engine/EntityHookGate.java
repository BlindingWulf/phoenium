package dev.phoenium.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stage 11 (2026-10-18) compatibility gate for the engine's tile threads. Nothing here references a Minecraft class.
 *
 * <p>The tile threads run non-player entity ticks (mob AI, projectiles), and with them every damage and death those ticks
 * cause, including a player's death to a mob. So a mod's entity damage/death callback or entity mixin runs on two or
 * three threads at once, against state that typical mods keep in plain maps (s11tdds-D43-1: 138 of 192 AFTER_DEATH
 * credits on tile threads and 10 overlapping entries into one plain map). Tile parallelism therefore stays on only when
 * every such hook belongs to a certified stack mod; with any other hook the tiles run on the server thread alone, exactly
 * as {@code phoenium.engine.workers=0} does (same phases, same order, vanilla's callback thread).
 *
 * <p>{@code phoenium.engine.foreignHooks}: {@code auto} (default) as above; {@code allow} keeps the tile threads anyway
 * (the operator accepts the risk; the foreign mods are still logged). Any other value is treated as {@code auto}
 * (so foreign hooks still mean serial). {@code phoenium.engine.foreignHooks.allowMods=a,b}: mod ids proven safe on tile threads.
 */
public final class EntityHookGate {
    private EntityHookGate() { }

    /**
     * Mods whose entity hooks were certified on the engine by the Stage 7-9 gates (the L5 stack, Phoenix's managed mods,
     * Phoenium itself and its test-free helpers). Fabric API modules ({@code fabric-*}) fire the events; they are certified
     * as dispatchers, while the listeners they call are judged by their own mod.
     */
    public static final Set<String> DEFAULT_CERTIFIED = Set.of(
        "minecraft", "java", "fabricloader", "fabric-api", "mixinextras", "phoenium", "phoenium-stage9-telemetry",
        "stage9-telemetry", "c2me", "lithium", "scalablelux", "servercore", "clumps", "chunky", "viafabric", "viaversion",
        "viabackwards", "geyser-fabric", "floodgate", "nochatreports", "phoenix-spawnprep");

    /** One hook: {@code kind} is {@code event} (a listener on an entity event) or {@code mixin} (a mixin target class). */
    public record Hook(String modId, String kind, String detail) { }

    /** {@code parallel}: whether the tile threads may run; {@code foreign}: the uncertified mods with entity hooks. */
    public record Decision(boolean parallel, List<String> foreign, String mode, List<Hook> foreignHooks) {
        public Decision(boolean parallel, List<String> foreign, String mode) {
            this(parallel, foreign, mode, List.of());
        }

        public String describe() {
            StringBuilder b = new StringBuilder("entityHooks=").append(parallel ? "PARALLEL" : "SERIAL")
                .append(" foreign=").append(foreign);
            if ("allow".equals(mode) && !foreign.isEmpty()) b.append(" override=allow");
            else if (!"auto".equals(mode) && !"allow".equals(mode)) b.append(" mode=").append(mode).append("(unknown: treated as auto)");
            return b.toString();
        }
    }

    public static Decision decide(List<Hook> hooks, Set<String> certified, String mode, Set<String> allowMods) {
        TreeSet<String> foreign = new TreeSet<>();
        List<Hook> foreignHooks = new ArrayList<>();
        for (Hook h : hooks) {
            if (certified(h.modId(), certified) || (h.modId() != null && allowMods.contains(h.modId()))) continue;
            foreign.add(h.modId() == null ? "?" : h.modId());
            foreignHooks.add(h);
        }
        String m = mode == null ? "auto" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        boolean parallel = foreign.isEmpty() || "allow".equals(m);
        return new Decision(parallel, List.copyOf(foreign), m, List.copyOf(foreignHooks));
    }

    /** Entity classes (the package net.minecraft.world.entity and below) and ServerPlayer, in dotted or internal form. */
    public static boolean isEntityTarget(String className) {
        if (className == null) return false;
        String c = className.replace('/', '.');
        return c.startsWith("net.minecraft.world.entity.") || c.equals("net.minecraft.server.level.ServerPlayer");
    }

    /** A listed id, or a Fabric API module ({@code fabric-...}). */
    public static boolean certified(String modId, Set<String> certified) {
        if (modId == null) return false;
        return certified.contains(modId) || modId.startsWith("fabric-") || modId.startsWith("c2me-");
    }
}
