package dev.phoenium.stage7repair.mixin;

import dev.phoenium.stage7repair.Stage7RepairProbe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the vanilla status broadcast before egg/snowball impact removal. */
@Mixin(ServerLevel.class)
abstract class ProjectileImpactEventObserver {
    @Inject(method="broadcastEntityEvent",at=@At("HEAD"),require=1)
    private void phoenium$nativeProjectileImpact(Entity entity,byte event,CallbackInfo ci) {
        Stage7RepairProbe.observeProjectileImpactEvent(entity,event);
    }
}
