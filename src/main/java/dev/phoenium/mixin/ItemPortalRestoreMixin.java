package dev.phoenium.mixin;

import dev.phoenium.live.NativePortalRestoreAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.item.ItemEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ItemEntity.class)
abstract class ItemPortalRestoreMixin implements NativePortalRestoreAccess {
    @Shadow private @Nullable EntityReference<Entity> thrower;

    @Override public EntityReference<?> phoenium$capturePortalRestoreReference() { return thrower; }

    @SuppressWarnings("unchecked")
    @Override public void phoenium$installPortalRestoreReference(EntityReference<?> reference) {
        thrower = (EntityReference<Entity>) reference;
    }
}
