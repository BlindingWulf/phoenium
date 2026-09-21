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

    @Test void freshGraphUsesInheritedRootFirstRecursiveStreamAndSynchronousManagerInsertion() throws Exception {
        ClassNode level = original(ServerLevel.class);
        assertTrue(level.methods.stream().noneMatch(m -> m.name.equals("addFreshEntityWithPassengers")));
        ClassNode accessor = original(net.minecraft.world.level.ServerLevelAccessor.class);
        MethodNode loop = method(accessor, "addFreshEntityWithPassengers", "(" + ENTITY + ")V");
        List<MethodInsnNode> calls = calls(loop);
        assertEquals(List.of("getSelfAndPassengers", "forEach"), calls.stream().map(c -> c.name).toList());
        assertTrue(Arrays.stream(loop.instructions.toArray()).filter(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::isInstance)
            .map(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::cast).flatMap(i -> Arrays.stream(i.bsmArgs))
            .anyMatch(arg -> arg instanceof org.objectweb.asm.Handle h && h.getName().equals("addFreshEntity")));
        ClassNode entity = original(Entity.class);
        assertEquals(List.of("of", "getIndirectPassengersStream", "concat"),
            calls(method(entity, "getSelfAndPassengers", "()Ljava/util/stream/Stream;")).stream().map(c -> c.name).toList());
        MethodNode descendants = method(entity, "getIndirectPassengersStream", "()Ljava/util/stream/Stream;");
        assertEquals(List.of("stream", "flatMap"), calls(descendants).stream().map(c -> c.name).toList());
        assertTrue(Arrays.stream(descendants.instructions.toArray()).filter(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::isInstance)
            .map(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::cast).flatMap(i -> Arrays.stream(i.bsmArgs))
            .anyMatch(arg -> arg instanceof org.objectweb.asm.Handle h && h.getName().equals("getSelfAndPassengers")));
        assertEquals(List.of("addEntity"), calls(method(level, "addFreshEntity", "(" + ENTITY + ")Z")).stream().map(c -> c.name).toList());
        assertTrue(calls(method(level, "addEntity", "(" + ENTITY + ")Z")).stream().anyMatch(c -> c.name.equals("addNewEntity")));
        ClassNode manager = original(PersistentEntitySectionManager.class);
        List<MethodInsnNode> insert = calls(method(manager, "addEntity", "(" + ACCESS + "Z)Z"));
        int storage = index(insert, "net/minecraft/world/level/entity/EntitySection", "add", "(" + ACCESS + ")V");
        int created = index(insert, "net/minecraft/world/level/entity/LevelCallback", "onCreated", "(Ljava/lang/Object;)V");
        int tracking = index(insert, manager.name, "startTracking", "(" + ACCESS + ")V");
        int ticking = index(insert, manager.name, "startTicking", "(" + ACCESS + ")V");
        assertTrue(storage >= 0 && storage < created && created < tracking && tracking < ticking);
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }

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

    @Test
    void nativeStorageWorldAndRemovalIdentitiesAreExact() throws Exception {
        ClassNode manager = original(PersistentEntitySectionManager.class);
        assertNotNull(method(manager, "storeChunkSections", "(JLjava/util/function/Consumer;)Z"));
        MethodNode add = method(manager, "addEntity", "(" + ACCESS + "Z)Z");
        List<MethodInsnNode> calls = Arrays.stream(add.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast).toList();
        assertTrue(index(calls, "net/minecraft/world/level/entity/EntitySection", "add", "(" + ACCESS + ")V")
            < index(calls, manager.name, "startTracking", "(" + ACCESS + ")V"));
        ClassNode callbacks = original(ServerLevel.class, "ServerLevel$EntityCallbacks.class");
        assertEquals("Lnet/minecraft/server/level/ServerLevel;", callbacks.fields.stream()
            .filter(field -> field.name.equals("this$0")).findFirst().orElseThrow().desc);
        ClassNode removal = original(PersistentEntitySectionManager.class, "PersistentEntitySectionManager$Callback.class");
        assertNotNull(method(removal, "onRemove", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"));
        assertEquals(ACCESS, removal.fields.stream().filter(field -> field.name.equals("entity")).findFirst().orElseThrow().desc);
        assertEquals("Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;", removal.fields.stream()
            .filter(field -> field.name.equals("this$0")).findFirst().orElseThrow().desc);
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
