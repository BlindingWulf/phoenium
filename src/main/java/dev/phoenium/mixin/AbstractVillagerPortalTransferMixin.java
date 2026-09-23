package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.live.NativePortalTransfer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The native trading reference is cleared before Entity.teleport. Preserve that
 * source mutation inside the retained source service operation. */
@Mixin(AbstractVillager.class)
abstract class AbstractVillagerPortalTransferMixin {
    @WrapOperation(method="teleport",at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/npc/villager/AbstractVillager;stopTrading()V"),
        require=1,allow=1)
    private void phoenium$portalStopTrading(AbstractVillager source, Operation<Void> original) {
        NativePortalTransfer.sourceEffect((Entity)(Object)this, "STOP_TRADING", () -> original.call(source));
    }
}
