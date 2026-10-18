package dev.phoenium.engine;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

/**
 * Stage 11: finds the entity hooks the tile threads would run (see {@link EntityHookGate}) once, at server start, after
 * every mod initializer has registered its listeners. Read-only: it inspects listener arrays and the mods' own mixin
 * files and changes nothing. Any failure of the scan itself counts as a foreign hook
 * ({@code scan-error}), so an unreadable setup runs the safe serial way.
 *
 * <p>Gate v2 (owner review 2026-10-18): a mixin counts only where it can run on a tile thread
 * ({@link EntityHookGate#isTileHook}: server-thread-only player methods do not); the mixins Mixin actually applied to
 * entity classes are cross-checked, which finds those that config plugins and programmatic configs add; and
 * {@link Watch} sees listeners registered after the scan.
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
        Set<String> seen = new HashSet<>();
        try {
            mixins(mods, hooks, seen);
        } catch (Throwable t) {
            hooks.add(new EntityHookGate.Hook("scan-error", "mixin", t.toString()));
        }
        // supplementary to the config scan: if Mixin's internals cannot be read, the decision rests on the scans above
        try {
            applied(mods, hooks, seen);
        } catch (Throwable t) {
            LOGGER.warn("PHOENIUM_COMPAT applied-mixin cross-check unavailable ({}); decision uses the mods' mixin configs only", t.toString());
        }
        EntityHookGate.Decision d = decide(hooks);
        for (EntityHookGate.Hook h : d.foreignHooks())
            LOGGER.info("PHOENIUM_COMPAT entity hook mod={} kind={} {}", h.modId(), h.kind(), h.detail());
        return d;
    }

    private static EntityHookGate.Decision decide(List<EntityHookGate.Hook> hooks) {
        String mode = System.getProperty("phoenium.engine.foreignHooks", "auto");
        Set<String> allow = new LinkedHashSet<>();
        for (String s : System.getProperty("phoenium.engine.foreignHooks.allowMods", "").split(","))
            if (!s.isBlank()) allow.add(s.trim());
        return EntityHookGate.decide(hooks, EntityHookGate.DEFAULT_CERTIFIED, mode, allow);
    }

    /**
     * The listener arrays of the entity events, re-read each server tick while the tile threads run, so a listener
     * registered after the start scan (a mod that registers late, or on demand) is judged like the others. The fields are
     * resolved once; a tick costs one field read per event.
     */
    static final class Watch {
        private final List<Object> events = new ArrayList<>();
        private final List<Field> fields = new ArrayList<>();
        private List<Object[]> last;

        Watch() throws ReflectiveOperationException {
            for (String[] spec : EVENTS) {
                Class<?> holder;
                try {
                    holder = Class.forName(spec[0]);
                } catch (ClassNotFoundException absent) {
                    continue;
                }
                for (int i = 1; i < spec.length; i++) {
                    Object event;
                    try {
                        event = holder.getField(spec[i]).get(null);
                    } catch (NoSuchFieldException absent) {
                        continue;
                    }
                    if (event == null) continue;
                    events.add(event);
                    fields.add(handlersField(event));
                }
            }
            last = arrays();
        }

        private List<Object[]> arrays() throws IllegalAccessException {
            List<Object[]> out = new ArrayList<>(fields.size());
            for (int i = 0; i < fields.size(); i++) out.add(fields.get(i).get(events.get(i)) instanceof Object[] a ? a : null);
            return out;
        }

        /**
         * Null while no listener array changed, or when every listener is certified (or allowed); otherwise the decision
         * with the current listeners. The mixins were judged at start and do not change once their classes are loaded.
         */
        EntityHookGate.Decision check() throws ReflectiveOperationException {
            List<Object[]> now = arrays();
            if (!EntityHookGate.anyChanged(last, now)) return null;
            last = now;
            List<EntityHookGate.Hook> hooks = new ArrayList<>();
            listeners(FabricLoader.getInstance().getAllMods(), hooks);
            EntityHookGate.Decision d = decide(hooks);
            if (d.foreign().isEmpty()) return null;
            for (EntityHookGate.Hook h : d.foreignHooks())
                LOGGER.info("PHOENIUM_COMPAT entity hook mod={} kind={} {} (seen after start)", h.modId(), h.kind(), h.detail());
            return d;
        }
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

    /** The listener array of a Fabric ArrayBackedEvent ({@code handlers}). */
    private static Object[] handlers(Object event) throws ReflectiveOperationException {
        Object v = handlersField(event).get(event);
        return v instanceof Object[] array ? array : new Object[0];
    }

    /** The {@code handlers} field, found by name up the class hierarchy. */
    private static Field handlersField(Object event) throws NoSuchFieldException {
        for (Class<?> c = event.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("handlers");
                f.setAccessible(true);
                return f;
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
     * nothing. A mod whose files cannot be read counts as a foreign hook. Every mixin class read is added to {@code seen}.
     */
    private static void mixins(Collection<ModContainer> mods, List<EntityHookGate.Hook> out, Set<String> seen) {
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
                        seen.add(cls);
                        byte[] bytes = Files.readAllBytes(file.get());
                        List<String> targets = new ArrayList<>();
                        for (String target : MixinTargets.hookTargets(bytes))
                            if (EntityHookGate.isEntityTarget(target)) targets.add(target.replace('/', '.'));
                        if (!targets.isEmpty() && tileHook(id, cls, targets, bytes)) entity.addAll(targets);
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

    /** {@link EntityHookGate#isTileHook} for one mixin class; a mixin it discounts is logged with its methods. */
    private static boolean tileHook(String mod, String mixinClass, List<String> targets, byte[] bytes) {
        List<String> injected = MixinTargets.injectedMethods(bytes);
        if (EntityHookGate.isTileHook(targets, injected)) return true;
        LOGGER.info("PHOENIUM_COMPAT entity hook ignored mod={} mixin={} targets={} methods={} reason={}", mod, mixinClass,
            targets, injected, injected.isEmpty() ? "no-injector" : "server-thread-only");
        return false;
    }

    /**
     * The mixins Mixin actually applied to entity classes (its class metadata cache, read once at start), for the ones the
     * config scan did not see: those a config plugin adds or a programmatic config declares. Each is judged like the
     * others, by its mod and its own class bytes; one whose bytes cannot be read counts as a hook.
     */
    private static void applied(Collection<ModContainer> mods, List<EntityHookGate.Hook> out, Set<String> seen)
            throws ReflectiveOperationException {
        long t0 = System.nanoTime();
        Field cacheField = ClassInfo.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
        List<Object> infos = copy(() -> new ArrayList<Object>(cache.values()));
        Map<String, TreeSet<String>> targetsOf = new TreeMap<>();
        Map<String, IMixinInfo> infoOf = new HashMap<>();
        int entityClasses = 0;
        for (Object o : infos) {
            if (!(o instanceof ClassInfo ci) || !EntityHookGate.isEntityTarget(ci.getName())) continue;
            entityClasses++;
            for (IMixinInfo mi : copy(() -> new ArrayList<IMixinInfo>(ci.getAppliedMixins()))) {
                String cls = mi.getClassName();
                if (cls == null || seen.contains(cls)) continue;
                targetsOf.computeIfAbsent(cls, k -> new TreeSet<>()).add(ci.getName().replace('/', '.'));
                infoOf.putIfAbsent(cls, mi);
            }
        }
        int judged = 0;
        for (Map.Entry<String, TreeSet<String>> e : targetsOf.entrySet()) {
            String cls = e.getKey();
            String mod = sourceMod(infoOf.get(cls));
            if (mod == null) mod = modOf(cls, mods);
            if (EntityHookGate.certified(mod, EntityHookGate.DEFAULT_CERTIFIED)) continue;
            judged++;
            List<String> targets = new ArrayList<>(e.getValue());
            String detail = "applied " + cls + " -> " + String.join(",", targets.subList(0, Math.min(targets.size(), 6)))
                + (targets.size() > 6 ? ",+" + (targets.size() - 6) : "");
            byte[] bytes = classBytes(cls, mod);
            if (bytes == null) out.add(new EntityHookGate.Hook(mod, "mixin", detail + " (class unreadable)"));
            else if (tileHook(mod, cls, targets, bytes)) out.add(new EntityHookGate.Hook(mod, "mixin", detail));
        }
        LOGGER.info("PHOENIUM_COMPAT applied-mixin cross-check entityClasses={} unseenMixins={} judged={} ms={}", entityClasses,
            targetsOf.size(), judged, String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e6));
    }

    /** Copies a collection that class loading on another thread may change, retrying a few times. */
    private static <T> List<T> copy(Supplier<List<T>> copier) {
        for (int attempt = 1; ; attempt++) {
            try {
                return copier.get();
            } catch (ConcurrentModificationException again) {
                if (attempt == 5) throw again;
                Thread.onSpinWait();
            }
        }
    }

    /** The loaded mod named by the mixin config's source (Fabric Loader names the declaring mod), or null. */
    private static String sourceMod(IMixinInfo mi) {
        try {
            IMixinConfigSource source = mi.getConfig() == null ? null : mi.getConfig().getSource();
            String id = source == null ? null : source.getId();
            return id != null && FabricLoader.getInstance().isModLoaded(id) ? id : null;
        } catch (RuntimeException | LinkageError unknown) {
            return null;
        }
    }

    /** A mixin class's bytes from its mod's files, else from the class loader's resources; null if neither has them. */
    private static byte[] classBytes(String cls, String mod) {
        String resource = cls.replace('.', '/') + ".class";
        try {
            if (mod != null) {
                Optional<ModContainer> c = FabricLoader.getInstance().getModContainer(mod);
                Optional<Path> p = c.isPresent() ? c.get().findPath(resource) : Optional.empty();
                if (p.isPresent()) return Files.readAllBytes(p.get());
            }
            try (InputStream in = EntityHookScan.class.getClassLoader().getResourceAsStream(resource)) {
                return in == null ? null : in.readAllBytes();
            }
        } catch (Exception unreadable) {
            return null;
        }
    }
}
