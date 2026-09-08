package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class RegionalTrackingAdapterContractTest {
    @org.junit.jupiter.api.Test
    void accessorsUseTheExactPinnedTargetFieldDescriptors() throws Exception {
        assertAccessorTypes(net.minecraft.server.level.ChunkMap.class, dev.phoenium.mixin.RegionalChunkMapAccess.class);
        assertAccessorTypes(Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity", false,
            net.minecraft.server.level.ChunkMap.class.getClassLoader()), dev.phoenium.mixin.RegionalTrackedEntityAccess.class);
        assertAccessorTypes(net.minecraft.world.ticks.LevelTicks.class, dev.phoenium.mixin.RegionalLevelTicksAccess.class);
    }

    private static void assertAccessorTypes(Class<?> target, Class<?> bridge) throws Exception {
        int verified = 0;
        for (var method : bridge.getDeclaredMethods()) {
            var accessor = method.getAnnotation(org.spongepowered.asm.mixin.gen.Accessor.class);
            if (accessor == null) continue;
            Class<?> descriptor = method.getParameterCount() == 0 ? method.getReturnType() : method.getParameterTypes()[0];
            assertEquals(target.getDeclaredField(accessor.value()).getType(), descriptor,
                () -> "Accessor descriptor must match " + target.getName() + "." + accessor.value());
            verified++;
        }
        assertTrue(verified > 0, "target descriptor verification must inspect actual accessor methods");
    }

    @Test
    void productionConfigurationInstallsEveryExactChunkMapTrackingAdapter() throws Exception {
        String configuration;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/phoenium.mixins.json"))) {
            configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String mixin : List.of("RegionalChunkMapAccess", "RegionalChunkMapTrackingMixin",
                "RegionalTrackedEntityAccess"))
            assertTrue(configuration.contains("\"" + mixin + "\""), "missing production tracker adapter " + mixin);
    }
}
