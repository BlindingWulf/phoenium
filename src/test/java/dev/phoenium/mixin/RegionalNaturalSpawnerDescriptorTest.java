package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import net.minecraft.world.level.NaturalSpawner;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

final class RegionalNaturalSpawnerDescriptorTest {
    private static final String METHOD = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;"
        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;"
        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;"
        + "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V";
    private static final String DESC = METHOD.substring(METHOD.indexOf('('));
    private static final String NEAREST_OWNER = "net/minecraft/server/level/ServerLevel";
    private static final String NEAREST_NAME = "getNearestPlayer";
    private static final String NEAREST_DESC = "(DDDDZ)Lnet/minecraft/world/entity/player/Player;";
    private static final String DISTANCE_OWNER = "net/minecraft/world/entity/player/Player";
    private static final String DISTANCE_NAME = "distanceToSqr";
    private static final String DISTANCE_DESC = "(DDD)D";

    @Test void installedSelectorsNameTheExactNativeOverloadAndBothPinnedInvocations() throws Exception {
        ClassNode nativeClass = new ClassNode();
        try (InputStream bytes = NaturalSpawner.class.getResourceAsStream("NaturalSpawner.class")) {
            assertNotNull(bytes); new ClassReader(bytes).accept(nativeClass, 0);
        }
        MethodNode nativeMethod = nativeClass.methods.stream()
            .filter(method -> method.name.equals("spawnCategoryForPosition") && method.desc.equals(DESC))
            .findFirst().orElseThrow();
        assertEquals(1, invocationCount(nativeMethod, NEAREST_OWNER, NEAREST_NAME, NEAREST_DESC));
        assertEquals(1, invocationCount(nativeMethod, DISTANCE_OWNER, DISTANCE_NAME, DISTANCE_DESC));
        assertEquals(1, invocationCount(nativeMethod, NEAREST_OWNER, "addFreshEntityWithPassengers", "(Lnet/minecraft/world/entity/Entity;)V"));

        Class<?> mixin = Class.forName("dev.phoenium.mixin.RegionalNaturalSpawnerMixin");
        assertSelector(mixin, "phoenium$nearestPlayer", NEAREST_OWNER, NEAREST_NAME, NEAREST_DESC);
        assertSelector(mixin, "phoenium$nearestDistance", DISTANCE_OWNER, DISTANCE_NAME, DISTANCE_DESC);
        assertSelector(mixin, "phoenium$passengerIdentity", NEAREST_OWNER, "addFreshEntityWithPassengers", "(Lnet/minecraft/world/entity/Entity;)V");
    }

    private static long invocationCount(MethodNode method, String owner, String name, String descriptor) {
        return Arrays.stream(method.instructions.toArray())
            .filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor))
            .count();
    }

    private static void assertSelector(Class<?> mixin, String handlerName, String owner,
            String invocationName, String invocationDescriptor) {
        Method handler = Arrays.stream(mixin.getDeclaredMethods())
            .filter(method -> method.getName().equals(handlerName)).findFirst().orElseThrow();
        WrapOperation operation = handler.getAnnotation(WrapOperation.class);
        assertNotNull(operation);
        assertArrayEquals(new String[] {METHOD}, operation.method());
        assertEquals(1, operation.require()); assertEquals(1, operation.expect());
        assertEquals(1, operation.at().length);
        assertEquals("L" + owner + ";" + invocationName + invocationDescriptor, operation.at()[0].target());
    }
}
