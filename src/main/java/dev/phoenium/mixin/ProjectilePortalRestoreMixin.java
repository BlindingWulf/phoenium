package dev.phoenium.mixin;

import dev.phoenium.live.NativePortalRestoreAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Projectile.class)
abstract class ProjectilePortalRestoreMixin implements NativePortalRestoreAccess {
    @Shadow protected @Nullable EntityReference<Entity> owner;

    @Override public EntityReference<?> phoenium$capturePortalRestoreReference() { return owner; }

    @SuppressWarnings("unchecked")
    @Override public void phoenium$installPortalRestoreReference(EntityReference<?> reference) {
        owner = (EntityReference<Entity>) reference;
    }
}
