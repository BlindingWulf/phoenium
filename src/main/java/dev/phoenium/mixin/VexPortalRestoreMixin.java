package dev.phoenium.mixin;

import dev.phoenium.live.NativePortalRestoreAccess;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Vex;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Vex.class)
abstract class VexPortalRestoreMixin implements NativePortalRestoreAccess {
    @Shadow private @Nullable EntityReference<LivingEntity> owner;

    @Override public EntityReference<?> phoenium$capturePortalRestoreReference() { return owner; }

    @SuppressWarnings("unchecked")
    @Override public void phoenium$installPortalRestoreReference(EntityReference<?> reference) {
        owner = (EntityReference<LivingEntity>) reference;
    }
}
