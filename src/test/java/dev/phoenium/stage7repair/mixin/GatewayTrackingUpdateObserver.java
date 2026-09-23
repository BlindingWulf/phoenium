package dev.phoenium.stage7repair.mixin;

import dev.phoenium.stage7repair.Stage7RepairProbe;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only witness for updates of the existing same-object native tracker. */
@Mixin(ServerEntity.class)
abstract class GatewayTrackingUpdateObserver {
    @Shadow @Final private Entity entity;

    @Inject(method = "sendChanges", at = @At("RETURN"), require = 1)
    private void stage7$trackingUpdateReturn(CallbackInfo ci) {
        if(entity.entityTags().contains("stage7_gateway_pig"))
            Stage7RepairProbe.observe("TRACKING_UPDATE_RETURN", entity);
    }
}
