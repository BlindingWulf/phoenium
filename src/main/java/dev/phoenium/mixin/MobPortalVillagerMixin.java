package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain Villager's gossip and unhappy-state tail after the Mob portal cut. */
@Mixin(Villager.class)
abstract class MobPortalVillagerMixin {
    @Inject(method="tick()V",at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/npc/villager/AbstractVillager;tick()V",shift=At.Shift.AFTER),
        cancellable=true,require=1,allow=1)
    private void phoenium$returnVillagerTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().mobPortalSuspended((Villager)(Object)this)) ci.cancel();
    }
}
