package dev.phoenium.mixin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.Inject;

/** Exact native proof for loaded/new entity ownership publication before tracker callbacks. */
final class ServerLevelEntityLifecycleTargetTest {
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String ACCESS = "Lnet/minecraft/world/level/entity/EntityAccess;";

    @Test
    void persistentManagerPublishesVisibleIdentityBeforeTheCommonTrackingCallback() throws Exception {
        ClassNode manager = original(PersistentEntitySectionManager.class);
        MethodNode start = method(manager, "startTracking", "(" + ACCESS + ")V");
        List<MethodInsnNode> calls = Arrays.stream(start.instructions.toArray())
            .filter(instruction -> instruction instanceof MethodInsnNode)
            .map(instruction -> (MethodInsnNode) instruction).toList();
        int visibleAdd = index(calls, "net/minecraft/world/level/entity/EntityLookup", "add", "(" + ACCESS + ")V");
        int callback = index(calls, "net/minecraft/world/level/entity/LevelCallback", "onTrackingStart", "(Ljava/lang/Object;)V");
        assertTrue(visibleAdd >= 0);
        assertTrue(callback > visibleAdd, "owner-published EntityLookup identity must precede onTrackingStart");

        ClassNode callbacks = original(ServerLevel.class, "ServerLevel$EntityCallbacks.class");
        assertNotNull(method(callbacks, "onTrackingStart", "(" + ENTITY + ")V"));
    }

    @Test
    void mixinTargetsTheConcreteCommonCallbackAtHeadExactlyOnce() throws Exception {
        Class<?> mixinType = Class.forName("dev.phoenium.mixin.ServerLevelEntityLifecycleMixin");
        ClassNode mixinClass = original(mixinType);
        AnnotationNode mixin = annotation(mixinClass, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of("net.minecraft.server.level.ServerLevel$EntityCallbacks"), value(mixin, "targets"));
        Method handler = Arrays.stream(mixinType.getDeclaredMethods())
            .filter(method -> method.getName().equals("phoenium$trackingStarted"))
            .findFirst().orElseThrow();
        Inject inject = handler.getAnnotation(Inject.class);
        assertNotNull(inject);
        assertArrayEquals(new String[] {"onTrackingStart(" + ENTITY + ")V"}, inject.method());
        assertEquals(1, inject.require());
        assertEquals(1, inject.expect());
        assertEquals(1, inject.at().length);
        assertEquals("HEAD", inject.at()[0].value());
    }

    private static int index(List<MethodInsnNode> calls, String owner, String name, String descriptor) {
        for (int index = 0; index < calls.size(); index++) {
            MethodInsnNode call = calls.get(index);
            if (call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) return index;
        }
        return -1;
    }

    private static ClassNode original(Class<?> type) throws Exception {
        return original(type, type.getSimpleName() + ".class");
    }

    private static ClassNode original(Class<?> type, String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream bytes = type.getResourceAsStream(resource)) {
            assertNotNull(bytes, resource);
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst().orElseThrow(() -> new AssertionError("missing native method " + type.name + "." + name + descriptor));
    }

    private static AnnotationNode annotation(ClassNode type, String descriptor) {
        return java.util.stream.Stream.of(type.visibleAnnotations, type.invisibleAnnotations)
            .filter(java.util.Objects::nonNull).flatMap(List::stream)
            .filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (annotation.values.get(index).equals(name)) return annotation.values.get(index + 1);
        throw new AssertionError("missing annotation value " + name);
    }
}
