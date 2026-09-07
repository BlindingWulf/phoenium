package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Actual world/cache installation and replacement, not a getter-derived ready flag. */
@Mixin(ServerLevel.class)
abstract class FullFinishingWorldContextMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void phoenium$world(CallbackInfo ci) {
        ServerLevel world = (ServerLevel) (Object) this;
        FullFinishingServices.forServer(world.getServer()).registerWorld(world);
    }
    @WrapMethod(method = "setEnvironmentAttributes")
    private EnvironmentAttributeSystem phoenium$environment(EnvironmentAttributeSystem value, Operation<EnvironmentAttributeSystem> original) {
        ServerLevel world = (ServerLevel) (Object) this;
        var service = FullFinishingServices.forServer(world.getServer());
        try (var turn = service.referenceAccess()) {
            EnvironmentAttributeSystem previous = original.call(value); service.registerWorld(world); return previous;
        }
    }
}
