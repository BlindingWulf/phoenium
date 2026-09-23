package dev.phoenium.live;

import net.minecraft.world.entity.EntityReference;

/** The four 26.2 restoreFrom overrides that copy a live reference after Entity.load. */
public interface NativePortalRestoreAccess {
    EntityReference<?> phoenium$capturePortalRestoreReference();
    void phoenium$installPortalRestoreReference(EntityReference<?> reference);
}
