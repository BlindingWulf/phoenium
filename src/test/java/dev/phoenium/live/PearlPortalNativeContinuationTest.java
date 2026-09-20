package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.mixin.PearlPortalProjectileAccess;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
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

    @Test void retainedHitInvokerBindsTheActualDeclaringClass() throws Exception {
        var nativeMethod = Projectile.class.getDeclaredMethod("hitTargetOrDeflectSelf", HitResult.class);
        assertTrue(java.lang.reflect.Modifier.isProtected(nativeMethod.getModifiers()));
        assertEquals(ProjectileDeflection.class, nativeMethod.getReturnType());
        assertThrows(NoSuchMethodException.class,
            () -> ThrowableProjectile.class.getDeclaredMethod("hitTargetOrDeflectSelf", HitResult.class));

        var access = classNode(PearlPortalProjectileAccess.class);
        var mixin = annotation(access, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertNotNull(mixin);
        assertEquals(List.of(Type.getType(Projectile.class)), annotationValue(mixin, "value"));
        var invoker = access.methods.stream()
            .filter(method -> method.name.equals("phoenium$invokeHitTargetOrDeflectSelf"))
            .findFirst().orElseThrow();
        assertEquals("(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;",
            invoker.desc);
        var binding = annotation(invoker, "Lorg/spongepowered/asm/mixin/gen/Invoker;");
        assertNotNull(binding);
        assertEquals("hitTargetOrDeflectSelf", annotationValue(binding, "value"));
    }

    @Test void allPinnedPortalMixinsAreProductionRegistered() throws Exception {
        String config;
        try (InputStream input = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            assertNotNull(input);
            config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String mixin : List.of("PearlPortalProcessorAccess", "PearlPortalEntityMixin",
                "PearlPortalProjectileAccess", "PearlPortalProjectileMixin",
                "PearlPortalThrowableProjectileMixin",
                "PearlPortalThrownEnderpearlMixin")) assertTrue(config.contains('"' + mixin + '"'), mixin);
    }

    private static MethodNode method(Class<?> type, String name) throws Exception {
        ClassNode node = classNode(type);
        List<MethodNode> matches = node.methods.stream().filter(method -> method.name.equals(name)).toList();
        assertEquals(1, matches.size(), type.getName() + '.' + name);
        return matches.getFirst();
    }

    private static ClassNode classNode(Class<?> type) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(input, type.getName());
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static AnnotationNode annotation(ClassNode node, String descriptor) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        return annotations.stream().filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations.stream().filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        assertNotNull(annotation.values);
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        fail("missing annotation value " + name + " in " + annotation.desc);
        return null;
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
