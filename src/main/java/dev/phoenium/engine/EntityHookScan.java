package dev.phoenium.engine;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 11: finds the entity hooks the tile threads would run (see {@link EntityHookGate}) once, at server start, after
 * every mod initializer has registered its listeners. Read-only: it inspects listener arrays and the mods' own mixin
 * files and changes nothing. Any failure of the scan itself counts as a foreign hook
 * ({@code scan-error}), so an unreadable setup runs the safe serial way.
 */
final class EntityHookScan {
    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");

    /** Fabric API events fired from entity damage, death, conversion and equipment paths (absent ones are skipped). */
    private static final String[][] EVENTS = {
        {"net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents", "ALLOW_DAMAGE", "AFTER_DAMAGE", "ALLOW_DEATH",
            "AFTER_DEATH", "MOB_CONVERSION"},
        {"net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents", "AFTER_KILLED_OTHER_ENTITY"},
        {"net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents", "EQUIPMENT_CHANGE"},
        {"net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents", "ALLOW_DEATH"},
    };

    private EntityHookScan() { }

    static EntityHookGate.Decision run() {
        List<EntityHookGate.Hook> hooks = new ArrayList<>();
        Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
        try {
            listeners(mods, hooks);
        } catch (Throwable t) {
            hooks.add(new EntityHookGate.Hook("scan-error", "event", t.toString()));
        }
        try {
            mixins(mods, hooks);
        } catch (Throwable t) {
            hooks.add(new EntityHookGate.Hook("scan-error", "mixin", t.toString()));
        }
        String mode = System.getProperty("phoenium.engine.foreignHooks", "auto");
        Set<String> allow = new LinkedHashSet<>();
        for (String s : System.getProperty("phoenium.engine.foreignHooks.allowMods", "").split(","))
            if (!s.isBlank()) allow.add(s.trim());
        EntityHookGate.Decision d = EntityHookGate.decide(hooks, EntityHookGate.DEFAULT_CERTIFIED, mode, allow);
        for (EntityHookGate.Hook h : d.foreignHooks())
            LOGGER.info("PHOENIUM_COMPAT entity hook mod={} kind={} {}", h.modId(), h.kind(), h.detail());
        return d;
    }

    // ---- Fabric event listeners

    private static void listeners(Collection<ModContainer> mods, List<EntityHookGate.Hook> out) throws ReflectiveOperationException {
        Map<String, String> cache = new HashMap<>();
        for (String[] spec : EVENTS) {
            Class<?> holder;
            try {
                holder = Class.forName(spec[0]);
            } catch (ClassNotFoundException absent) {
                continue;
            }
            for (int i = 1; i < spec.length; i++) {
                Field f;
                try {
                    f = holder.getField(spec[i]);
                } catch (NoSuchFieldException absent) {
                    continue;
                }
                Object event = f.get(null);
                if (event == null) continue;
                for (Object handler : handlers(event)) {
                    if (handler == null) continue;
                    String host = hostClass(handler.getClass().getName());
                    String mod = cache.computeIfAbsent(host, h -> modOf(h, mods));
                    out.add(new EntityHookGate.Hook(mod, "event", holder.getSimpleName() + "." + spec[i] + " " + host));
                }
            }
        }
    }

    /** The listener array of a Fabric ArrayBackedEvent ({@code handlers}), found by name up the class hierarchy. */
    private static Object[] handlers(Object event) throws ReflectiveOperationException {
        for (Class<?> c = event.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("handlers");
                f.setAccessible(true);
                Object v = f.get(event);
                return v instanceof Object[] array ? array : new Object[0];
            } catch (NoSuchFieldException next) {
                // keep looking
            }
        }
        throw new NoSuchFieldException("handlers in " + event.getClass().getName());
    }

    /** A lambda or hidden class's host: the part before {@code $$Lambda} (or {@code /} for hidden classes). */
    static String hostClass(String name) {
        int i = name.indexOf("$$Lambda");
        if (i > 0) name = name.substring(0, i);
        int slash = name.indexOf('/');
        return slash > 0 ? name.substring(0, slash) : name;
    }

    /** The mod whose jar holds the class file; nested (jar-in-jar) mods are containers of their own. */
    private static String modOf(String className, Collection<ModContainer> mods) {
        String resource = className.replace('.', '/') + ".class";
        String found = null;
        for (ModContainer mod : mods) {
            try {
                if (mod.findPath(resource).isPresent()) {
                    String id = mod.getMetadata().getId();
                    // prefer the most specific (nested) container over a parent that also sees the class
                    if (found == null || mod.getContainingMod().isPresent()) found = id;
                }
            } catch (RuntimeException ignored) {
                // a container that cannot be searched does not own the class
            }
        }
        return found;
    }

    // ---- mixins

    /**
     * Entity mixins of the mods that are not certified, read from each mod's own fabric.mod.json, mixin configs and mixin
     * class files (see {@link MixinTargets}). Certified mods are not read at all, so the stack without add-ons parses
     * nothing. A mod whose files cannot be read counts as a foreign hook.
     */
    private static void mixins(Collection<ModContainer> mods, List<EntityHookGate.Hook> out) {
        for (ModContainer mod : mods) {
            String id = mod.getMetadata().getId();
            if (EntityHookGate.certified(id, EntityHookGate.DEFAULT_CERTIFIED)) continue;
            try {
                Optional<Path> fmj = mod.findPath("fabric.mod.json");
                if (fmj.isEmpty()) continue;
                for (String config : MixinTargets.modConfigs(Files.readString(fmj.get(), StandardCharsets.UTF_8))) {
                    Optional<Path> cfg = mod.findPath(config);
                    if (cfg.isEmpty()) continue;
                    TreeSet<String> entity = new TreeSet<>();
                    for (String cls : MixinTargets.configClasses(Files.readString(cfg.get(), StandardCharsets.UTF_8))) {
                        Optional<Path> file = mod.findPath(cls.replace('.', '/') + ".class");
                        if (file.isEmpty()) continue;
                        for (String target : MixinTargets.hookTargets(Files.readAllBytes(file.get())))
                            if (EntityHookGate.isEntityTarget(target)) entity.add(target.replace('/', '.'));
                    }
                    if (entity.isEmpty()) continue;
                    List<String> shown = new ArrayList<>(entity).subList(0, Math.min(entity.size(), 6));
                    out.add(new EntityHookGate.Hook(id, "mixin", config + " -> " + String.join(",", shown)
                        + (entity.size() > 6 ? ",+" + (entity.size() - 6) : "")));
                }
            } catch (Exception | LinkageError unreadable) {
                out.add(new EntityHookGate.Hook(id, "mixin", "unreadable: " + unreadable));
            }
        }
    }
}
