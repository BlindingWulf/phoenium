package dev.phoenium.stage7repair.mixin;
import dev.phoenium.stage7repair.Stage7RepairProbe;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
abstract class RemovalObserver {
    @Inject(method="setRemoved",at=@At("RETURN"),require=1)
    private void removed(Entity.RemovalReason reason,CallbackInfo ci){Stage7RepairProbe.observe("SET_REMOVED_RETURN",(Entity)(Object)this);}
}
