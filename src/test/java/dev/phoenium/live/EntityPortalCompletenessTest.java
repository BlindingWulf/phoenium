package dev.phoenium.live;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Static closure of the non-Mob entity census: from the pinned bytecode, decide for every concrete entity class
 * whether its tick chain can reach Entity.handlePortal. Every class that can (and whose canUsePortal is not
 * constant false) must be covered by a retained-portal manager. A class that would still run the destination
 * lookup inside an owner tick fails here by name.
 */
class EntityPortalCompletenessTest {
    private static final Map<String, ClassNode> NODES = new HashMap<>();

    @Test void everyEntityClassThatCanReachHandlePortalIsManagedOrExplicitlyAccountedFor() throws Exception {
        File jar = new File(Entity.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var unmanaged = new TreeMap<String, String>();
        var neverReaches = new TreeSet<String>();
        var contactless = new TreeSet<String>();
        int concrete = 0;
        try (JarFile file = new JarFile(jar)) {
            for (Enumeration<java.util.jar.JarEntry> e = file.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (!name.startsWith("net/minecraft/world/entity/") || !name.endsWith(".class")) continue;
                Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, Entity.class.getClassLoader());
                if (!Entity.class.isAssignableFrom(type) || type == Entity.class || Modifier.isAbstract(type.getModifiers())
                        || type.isInterface() || Mob.class.isAssignableFrom(type)
                        || net.minecraft.world.entity.player.Player.class.isAssignableFrom(type)) continue;
                concrete++;
                String internal = name.substring(0, name.length() - 6);
                if (!reachesHandlePortal(internal)) { neverReaches.add(type.getSimpleName()); continue; }
                if (canUsePortalConstantFalse(internal)) { neverReaches.add(type.getSimpleName() + "(canUsePortal=false)"); continue; }
                if (isManaged(type)) continue;
                // Portal contact comes only from Entity.applyEffectsFromBlocks (move, or an explicit call from tick).
                var contact = contactCalls(internal);
                if (contact.isEmpty()) contactless.add(type.getSimpleName());
                else unmanaged.put(type.getName(), "reaches handlePortal, can make portal contact via " + contact);
            }
        }
        System.out.println("ENTITY_PORTAL_COMPLETENESS concrete=" + concrete + " neverReaches=" + neverReaches
            + " reachesButNeverMakesContact=" + contactless);
        // These classes call handlePortal but nothing in their tick chain can ever move them into a portal block
        // (no Entity.applyEffectsFromBlocks and no MoverType move), so the destination lookup is never asked.
        assertEquals(new TreeSet<>(java.util.List.of("AreaEffectCloud", "EvokerFangs", "EyeOfEnder", "LightningBolt",
            "LlamaSpit", "OminousItemSpawner")), contactless);
        assertTrue(concrete >= 40, "scan found too few entity classes: " + concrete);
        assertEquals(new TreeMap<String, String>(), unmanaged,
            "entity classes that would run the portal destination lookup inside an owner tick");
    }

    /** Dedicated managers keyed by hierarchy, the generic manager by exact reviewed plan. */
    private static boolean isManaged(Class<?> type) {
        return EntityPortalTransitionManager.managedElsewhere(type) || EntityPortalTransitionManager.supportsClass(type)
            || net.minecraft.world.entity.projectile.arrow.AbstractArrow.class.isAssignableFrom(type);
    }

    /** True iff running tick() from this class can execute Entity.handlePortal (directly or through Entity.tick). */
    private static boolean reachesHandlePortal(String internal) throws Exception {
        for (String cls = internal; cls != null; ) {
            if (cls.equals("net/minecraft/world/entity/Entity")) return true;   // Entity.tick -> baseTick -> handlePortal
            ClassNode node = node(cls);
            MethodNode tick = node.methods.stream().filter(m -> m.name.equals("tick") && m.desc.equals("()V")).findFirst().orElse(null);
            if (tick != null) {
                boolean direct = false, sup = false;
                for (var i : tick.instructions) if (i instanceof MethodInsnNode c) {
                    if (c.name.equals("handlePortal")) direct = true;
                    if (c.getOpcode() == Opcodes.INVOKESPECIAL && c.name.equals("tick") && c.owner.equals(node.superName)) sup = true;
                    if (c.name.equals("baseTick") && c.owner.equals(cls)) sup = true;
                }
                if (direct) return true;
                if (!sup) return false;   // tick does not continue into a parent tick
            }
            cls = node.superName;
        }
        return false;
    }

    /** Calls in the tick chain (up to Entity) that can trigger Entity.applyEffectsFromBlocks, hence portal contact. */
    private static java.util.Set<String> contactCalls(String internal) throws Exception {
        var found = new TreeSet<String>();
        for (String cls = internal; cls != null && !cls.equals("net/minecraft/world/entity/Entity"); cls = node(cls).superName) {
            for (var m : node(cls).methods) {
                if (!(m.name.equals("tick") || m.name.equals("baseTick") || m.name.equals("move") || m.name.equals("aiStep")
                        || m.name.startsWith("lambda$tick") || m.name.equals("onHit") || m.name.equals("hitTargetOrDeflectSelf"))) continue;
                for (var i : m.instructions) if (i instanceof MethodInsnNode c
                        && (c.name.equals("applyEffectsFromBlocks") || c.name.equals("checkInsideBlocks")
                            || c.name.equals("move") && c.desc.startsWith("(Lnet/minecraft/world/entity/MoverType;")))
                    found.add(cls.substring(cls.lastIndexOf('/') + 1) + "." + m.name + "->" + c.name);
            }
        }
        return found;
    }

    private static boolean canUsePortalConstantFalse(String internal) throws Exception {
        for (String cls = internal; cls != null && !cls.equals("net/minecraft/world/entity/Entity"); cls = node(cls).superName) {
            MethodNode m = node(cls).methods.stream().filter(x -> x.name.equals("canUsePortal") && x.desc.equals("(Z)Z")).findFirst().orElse(null);
            if (m == null) continue;
            var real = new java.util.ArrayList<Integer>();
            for (var i : m.instructions) if (i.getOpcode() >= 0) real.add(i.getOpcode());
            return real.equals(java.util.List.of(Opcodes.ICONST_0, Opcodes.IRETURN));
        }
        return false;
    }

    private static ClassNode node(String cls) throws Exception {
        ClassNode cached = NODES.get(cls);
        if (cached != null) return cached;
        try (InputStream in = EntityPortalCompletenessTest.class.getResourceAsStream("/" + cls + ".class")) {
            if (in == null) throw new IllegalStateException("class not on test classpath: " + cls);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            NODES.put(cls, node);
            return node;
        }
    }
}
