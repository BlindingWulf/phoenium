package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.PlayerRespawnHandoff;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import java.util.Set;
import java.util.UUID;

/** Journal only the respawn storage prefix where vanilla has not installed its removal callback. */
@Mixin(PersistentEntitySectionManager.class)
abstract class PlayerRespawnStorageMixin<T extends EntityAccess> {
    @Shadow @Final private Set<UUID> knownUuids;
    @Shadow @Final private EntitySectionStorage<T> sectionStorage;

    @WrapOperation(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;addEntityUuid(Lnet/minecraft/world/level/entity/EntityAccess;)Z"), require = 1, allow = 1)
    private boolean phoenium$respawnStorageClaim(PersistentEntitySectionManager<T> manager, T entity, Operation<Boolean> original) {
        if (entity instanceof ServerPlayer player && PlayerRespawnHandoff.retired(player))
            throw new dev.phoenium.chunk.ChunkProtocolException("retired respawn incarnation cannot enter storage");
        boolean admitted = original.call(manager, entity);
        if (admitted && entity instanceof ServerPlayer player) {
            var handoff = FullFinishingServices.forServer(player.level().getServer()).scoreboard.respawn(player);
            if (handoff != null) {
                long sectionKey = SectionPos.asLong(player.blockPosition());
                handoff.storageClaimed(player, () -> {
                    var section = sectionStorage.getSection(sectionKey);
                    if (section != null) {
                        var sameId = section.getEntities().filter(value -> value.getId() == player.getId()).toList();
                        if (sameId.stream().anyMatch(value -> value != player))
                            throw new dev.phoenium.chunk.ChunkProtocolException("respawn section cleanup found a newer incarnation");
                        if (sameId.stream().anyMatch(value -> value == player)) section.remove(entity);
                        if (section.isEmpty()) sectionStorage.remove(sectionKey);
                    }
                    knownUuids.remove(handoff.uuid);
                    FullEntityFacts.respawnPrefixUnstored(player, this);
                });
            }
        }
        return admitted;
    }
    @WrapOperation(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/EntityAccess;setLevelCallback(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V"), require = 1, allow = 1)
    private void phoenium$respawnCallbackInstalled(EntityAccess entity, EntityInLevelCallback callback, Operation<Void> original) {
        original.call(entity, callback);
        if (entity instanceof ServerPlayer player) {
            var handoff = FullFinishingServices.forServer(player.level().getServer()).scoreboard.respawn(player);
            if (handoff != null) handoff.storage(player).callbackInstalled();
        }
    }
}
