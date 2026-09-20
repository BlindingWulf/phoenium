package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullServerShutdown;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class FailedStopServerLevelMixin {
    @Inject(method = "close()V", at = @At("HEAD"), require = 1, expect = 1)
    private void phoenium$retirePortalTransitions(CallbackInfo ci) {
        Phoenium.liveRuntime().onWorldClosing((ServerLevel) (Object) this);
    }

    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;close()V"), require = 1, expect = 1)
    private void phoenium$closeLevel(ServerLevel level, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(level));
    }

    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;close()V"), require = 1, expect = 1)
    private void phoenium$closeEntities(PersistentEntitySectionManager<?> entities, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(entities));
    }
}
