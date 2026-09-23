package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.Phoenium;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;

/** Both exact decorative living types inherit LivingEntity.tick in the pinned profile. */
@Mixin(LivingEntity.class)
abstract class ArmorStandPortalTickMixin {
    @WrapMethod(method="tick()V")
    private void phoenium$armorStandTick(Operation<Void> original) {
        if (!((Object)this instanceof ArmorStand || (Object)this instanceof Mannequin)) {
            original.call();return;
        }
        LivingEntity stand=(LivingEntity)(Object)this;
        if (Phoenium.liveRuntime().armorStandPortalSuspended(stand)) return;
        Phoenium.liveRuntime().beginArmorStandPortalTick(stand);
        try {original.call();Phoenium.liveRuntime().completeArmorStandPortalTick(stand);}
        finally {Phoenium.liveRuntime().finishArmorStandPortalTick(stand);}
    }
}
