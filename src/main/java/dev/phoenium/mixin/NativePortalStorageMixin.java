package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.live.NativePortalTransfer;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Exact journal of the non-player transfer's unpublished native storage prefix. */
@Mixin(PersistentEntitySectionManager.class)
abstract class NativePortalStorageMixin<T extends EntityAccess> {
    @Shadow @Final private Set<UUID> knownUuids;
    @Shadow @Final private EntitySectionStorage<T> sectionStorage;
    @WrapOperation(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;addEntityUuid(Lnet/minecraft/world/level/entity/EntityAccess;)Z"), require = 1, allow = 1)
    private boolean phoenium$portalClaim(PersistentEntitySectionManager<T> manager, T value, Operation<Boolean> original) {
        boolean admitted = original.call(manager, value);
        if (admitted && value instanceof Entity entity && NativePortalTransfer.inserting(entity)) {
            long sectionKey = SectionPos.asLong(entity.blockPosition());
            UUID uuid = entity.getUUID();
            NativePortalTransfer.storageClaimed(entity, () -> {
                var section = sectionStorage.getSection(sectionKey);
                if (section != null) {
                    var matching = section.getEntities().filter(other -> other.getId() == entity.getId() || other.getUUID().equals(uuid)).toList();
                    if (matching.stream().anyMatch(other -> other != entity))
                        throw new IllegalStateException("portal storage cleanup refused a newer incarnation");
                    if (matching.stream().anyMatch(other -> other == entity)) section.remove(value);
                    if (section.isEmpty()) sectionStorage.remove(sectionKey);
                }
                knownUuids.remove(uuid);
                FullEntityFacts.respawnPrefixUnstored(entity, this);
            });
        }
        return admitted;
    }
    @WrapOperation(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/EntityAccess;setLevelCallback(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V"), require = 1, allow = 1)
    private void phoenium$portalCallback(EntityAccess entity, EntityInLevelCallback callback, Operation<Void> original) {
        original.call(entity, callback);
        if (entity instanceof Entity value) NativePortalTransfer.callbackInstalled(value);
    }
}
