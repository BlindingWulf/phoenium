package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Both cache-producing reads and invalidation are original synchronous operations. */
@Mixin(EnvironmentAttributeSystem.class)
abstract class FullFinishingEnvironmentMixin {
    @WrapMethod(method = "invalidateTickCache()V")
    private void phoenium$invalidate(Operation<Void> original) {
        try (var turn = FullFinishingServices.environment(this)) { original.call(); }
    }
    @WrapMethod(method = "getDimensionValue")
    private <T> T phoenium$dimension(EnvironmentAttribute<T> attribute, Operation<T> original) {
        try (var turn = FullFinishingServices.environment(this)) { return original.call(attribute); }
    }
    @WrapMethod(method = "getValue")
    private <T> T phoenium$value(EnvironmentAttribute<T> attribute, Vec3 position, SpatialAttributeInterpolator interpolator, Operation<T> original) {
        try (var turn = FullFinishingServices.environment(this)) { return original.call(attribute, position, interpolator); }
    }
}
