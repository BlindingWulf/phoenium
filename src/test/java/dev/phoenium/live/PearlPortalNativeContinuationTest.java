package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class PearlPortalNativeContinuationTest {
    @Test void pinnedNativePrefixAndSuffixCallShapeIsStillExact() throws Exception {
        assertOrdered(method(ThrowableProjectile.class, "tick"),
            "net/minecraft/world/entity/projectile/ProjectileUtil.getHitResultOnMoveVector",
            "net/minecraft/world/entity/projectile/ThrowableProjectile.setPos",
            "net/minecraft/world/entity/projectile/Projectile.tick",
            "net/minecraft/world/entity/projectile/ThrowableProjectile.hitTargetOrDeflectSelf");
        assertOrdered(method(Entity.class, "baseTick"),
            "net/minecraft/world/entity/Entity.handlePortal",
            "net/minecraft/world/entity/Entity.updateFluidInteraction",
            "net/minecraft/world/entity/Entity.updateSwimming",
            "net/minecraft/world/entity/Entity.checkBelowWorld");
        assertOrdered(method(Projectile.class, "tick"),
            "net/minecraft/world/entity/projectile/Projectile.checkLeftOwner",
            "net/minecraft/world/entity/Entity.tick");
        assertOrdered(method(ThrownEnderpearl.class, "tick"),
            "net/minecraft/world/entity/projectile/throwableitemprojectile/ThrowableItemProjectile.tick",
            "net/minecraft/server/level/ServerPlayer.registerAndUpdateEnderPearlTicket");
    }

    @Test void retainedContinuationCallsOnlyTypedSuffixSeams() throws Exception {
        List<String> calls = calls(method(RetainedPearlTickContinuation.class, "resume"));
        assertTrue(calls.contains("dev/phoenium/live/PearlPortalNativeAccess$EntitySuffix.phoenium$resumePearlPortalBaseTickSuffix"));
        assertTrue(calls.contains("dev/phoenium/live/PearlPortalNativeAccess$ProjectileSuffix.phoenium$resumePearlPortalProjectileTickSuffix"));
        assertTrue(calls.contains("dev/phoenium/live/PearlPortalNativeAccess$ThrowableSuffix.phoenium$resumePearlPortalThrowableTickSuffix"));
        assertTrue(calls.contains("dev/phoenium/live/PearlPortalNativeAccess$PearlSuffix.phoenium$resumePearlPortalPearlTickSuffix"));
        assertFalse(calls.stream().anyMatch(call -> call.endsWith(".tick") || call.endsWith(".getPortalDestination")),
            "a retained suffix must never replay the native prefix or destination preparation");
    }

    @Test void allPinnedPortalMixinsAreProductionRegistered() throws Exception {
        String config;
        try (InputStream input = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            assertNotNull(input);
            config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String mixin : List.of("PearlPortalProcessorAccess", "PearlPortalEntityMixin",
                "PearlPortalProjectileMixin", "PearlPortalThrowableProjectileMixin",
                "PearlPortalThrownEnderpearlMixin")) assertTrue(config.contains('"' + mixin + '"'), mixin);
    }

    private static MethodNode method(Class<?> type, String name) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(input, type.getName());
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        List<MethodNode> matches = node.methods.stream().filter(method -> method.name.equals(name)).toList();
        assertEquals(1, matches.size(), type.getName() + '.' + name);
        return matches.getFirst();
    }

    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) result.add(call.owner + '.' + call.name);
        return result;
    }

    private static void assertOrdered(MethodNode method, String... expected) {
        List<String> calls = calls(method);
        int previous = -1;
        for (String call : expected) {
            int found = calls.indexOf(call);
            assertTrue(found > previous, call + " is out of order in " + method.name + ": " + calls);
            previous = found;
        }
    }
}
