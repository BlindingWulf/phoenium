package dev.phoenium.live;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarFile;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Closes the mob caller-profile census in code: every concrete Mob class of the pinned 26.2 jar must be
 * selected by MobPortalTransitionManager (legacy exact sets or reviewed outer tails). A native class that
 * would still fall through to a destination search under owner authority fails this test by name.
 */
class MobPortalCallerCompletenessTest {
    /**
     * Mobs that can never reach a portal teleport (Entity.canUsePortal is false for them, so
     * Entity.handlePortal never asks for a destination): the two bosses.
     */
    private static final List<String> NEVER_ENTER_PORTALS = List.of(
        "net.minecraft.world.entity.boss.enderdragon.EnderDragon",
        "net.minecraft.world.entity.boss.wither.WitherBoss");

    @Test void everyConcreteMobClassIsSelectedOrNeverEntersAPortal() throws Exception {
        File jar = new File(Mob.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var unselected = new TreeSet<String>();
        var scanned = new TreeSet<String>();
        int concrete = 0;
        try (JarFile file = new JarFile(jar)) {
            for (Enumeration<java.util.jar.JarEntry> e = file.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (!name.startsWith("net/minecraft/world/entity/") || !name.endsWith(".class")) continue;
                Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, Mob.class.getClassLoader());
                if (!Mob.class.isAssignableFrom(type) || type == Mob.class || Modifier.isAbstract(type.getModifiers())
                        || type.isInterface()) continue;
                concrete++; scanned.add(type.getSimpleName());
                if (!MobPortalTransitionManager.supportsClass(type)) unselected.add(type.getName());
            }
        }
        assertTrue(concrete >= 80, "scan found too few Mob classes: " + concrete);
        for (String known : List.of("Pig", "Creeper", "Wolf", "Dolphin", "Axolotl", "Camel", "SulfurCube", "Warden", "EnderDragon"))
            assertTrue(scanned.contains(known), "scan missed a known Mob class: " + known);
        unselected.removeAll(NEVER_ENTER_PORTALS);
        assertEquals(new TreeSet<String>(), unselected, "Mob classes that would fall through to a native destination search");
    }

    @Test void theTwoExcludedBossesDeclareNoPortalUse() throws Exception {
        for (String name : NEVER_ENTER_PORTALS) {
            Class<?> type = Class.forName(name, false, Mob.class.getClassLoader());
            var canUse = type.getMethod("canUsePortal", boolean.class);
            assertNotEquals(net.minecraft.world.entity.Entity.class, canUse.getDeclaringClass(),
                name + " must override canUsePortal");
        }
    }
}
