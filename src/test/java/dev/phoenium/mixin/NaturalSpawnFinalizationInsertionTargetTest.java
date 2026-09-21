package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.NaturalSpawner;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned 26.2 proof for every direct insertion in the audited zombie finalization family. */
final class NaturalSpawnFinalizationInsertionTargetTest {
    private static final String FINALIZE_DESC = "(Lnet/minecraft/world/level/ServerLevelAccessor;"
        + "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/EntitySpawnReason;"
        + "Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;";
    private static final String ACCESSOR = "net/minecraft/world/level/ServerLevelAccessor";
    private static final String ENTITY_DESC = "(Lnet/minecraft/world/entity/Entity;)Z";
    private static final String BULK_DESC = "(Lnet/minecraft/world/entity/Entity;)V";
    private static final String SPAWN_DESC = "(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;"
        + "Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;"
        + "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V";

    @Test void directVehicleInsertionsFollowAttachmentAndAreExactlyHooked() throws Exception {
        for (Class<?> nativeType : List.of(Zombie.class, Drowned.class, Husk.class)) {
            MethodNode finalize = method(original(nativeType), "finalizeSpawn", FINALIZE_DESC);
            List<MethodInsnNode> calls = calls(finalize);
            int attach = firstNamed(calls, "startRiding");
            int direct = index(calls, ACCESSOR, "addFreshEntity", ENTITY_DESC);
            assertTrue(attach >= 0 && direct > attach, nativeType.getName());
            assertEquals(1, calls.stream().filter(call -> call.owner.equals(ACCESSOR)
                && call.name.equals("addFreshEntity") && call.desc.equals(ENTITY_DESC)).count(), nativeType.getName());
        }
        Class<?> mixinType = Class.forName("dev.phoenium.mixin.NaturalSpawnFinalizationInsertionMixin");
        AnnotationNode mixin = annotation(original(mixinType), "Lorg/spongepowered/asm/mixin/Mixin;");
        @SuppressWarnings("unchecked") List<Type> targets = (List<Type>) value(mixin, "value");
        assertEquals(Set.of(Type.getInternalName(Zombie.class), Type.getInternalName(Drowned.class), Type.getInternalName(Husk.class)),
            targets.stream().map(Type::getInternalName).collect(java.util.stream.Collectors.toSet()));
        assertWrap(mixinType, "phoenium$directFinalizationInsertion", "addFreshEntity", ENTITY_DESC);
    }

    @Test void huskHelperBulkExtendsTheSameGraphBeforeTheOuterCandidateInsertion() throws Exception {
        List<MethodInsnNode> husk = calls(method(original(Husk.class), "finalizeSpawn", FINALIZE_DESC));
        int direct = index(husk, ACCESSOR, "addFreshEntity", ENTITY_DESC);
        int secondAttach = lastNamed(husk, "startRiding");
        int helperBulk = index(husk, ACCESSOR, "addFreshEntityWithPassengers", BULK_DESC);
        assertTrue(direct >= 0 && direct < secondAttach && secondAttach < helperBulk);
        assertWrap(Class.forName("dev.phoenium.mixin.HuskNaturalSpawnFinalizationInsertionMixin"),
            "phoenium$finalizationBulkInsertion", "addFreshEntityWithPassengers", BULK_DESC);
    }

    @Test void naturalSpawnerFinalizesThenPerformsTheLaterOuterBulkInsertionAndCallback() throws Exception {
        List<MethodInsnNode> calls = calls(method(original(NaturalSpawner.class), "spawnCategoryForPosition", SPAWN_DESC));
        int finalize = index(calls, "net/minecraft/world/entity/Mob", "finalizeSpawn", FINALIZE_DESC);
        int bulk = index(calls, "net/minecraft/server/level/ServerLevel", "addFreshEntityWithPassengers", BULK_DESC);
        int after = firstNamed(calls, "run");
        assertTrue(finalize >= 0 && finalize < bulk && bulk < after);
    }

    @Test void bothNarrowFinalizationMixinsAreRegisteredForProduction() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            assertNotNull(input); String config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(config.contains("NaturalSpawnFinalizationInsertionMixin"));
            assertTrue(config.contains("HuskNaturalSpawnFinalizationInsertionMixin"));
        }
    }

    private static void assertWrap(Class<?> mixin, String methodName, String invocation, String descriptor) {
        Method handler = Arrays.stream(mixin.getDeclaredMethods()).filter(method -> method.getName().equals(methodName))
            .findFirst().orElseThrow();
        WrapOperation operation = handler.getAnnotation(WrapOperation.class); assertNotNull(operation);
        assertEquals(1, operation.require()); assertEquals(1, operation.expect());
        assertEquals("L" + ACCESSOR + ";" + invocation + descriptor, operation.at()[0].target());
    }
    private static int index(List<MethodInsnNode> calls, String owner, String name, String desc) {
        for (int index = 0; index < calls.size(); index++) {
            MethodInsnNode call = calls.get(index);
            if (call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) return index;
        }
        return -1;
    }
    private static int firstNamed(List<MethodInsnNode> calls, String name) {
        for (int index = 0; index < calls.size(); index++) if (calls.get(index).name.equals(name)) return index;
        return -1;
    }
    private static int lastNamed(List<MethodInsnNode> calls, String name) {
        for (int index = calls.size() - 1; index >= 0; index--) if (calls.get(index).name.equals(name)) return index;
        return -1;
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast).toList();
    }
    private static ClassNode original(Class<?> type) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(bytes); new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }
    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst().orElseThrow();
    }
    private static AnnotationNode annotation(ClassNode type, String descriptor) {
        return java.util.stream.Stream.of(type.visibleAnnotations, type.invisibleAnnotations)
            .filter(Objects::nonNull).flatMap(List::stream).filter(a -> a.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static Object value(AnnotationNode annotation, String name) {
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (annotation.values.get(index).equals(name)) return annotation.values.get(index + 1);
        throw new AssertionError("missing annotation value " + name);
    }
}
