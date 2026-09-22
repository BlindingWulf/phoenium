package dev.phoenium.stage7repair.mixin;
import dev.phoenium.stage7repair.Stage7RepairProbe;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ThrownEnderpearl.class)
abstract class PearlHitObserver {
    @Inject(method="onHit",at=@At("HEAD"),require=1)
    private void hit(HitResult result,CallbackInfo ci){Stage7RepairProbe.observe("PEARL_NATIVE_HIT",(Entity)(Object)this);}
}
