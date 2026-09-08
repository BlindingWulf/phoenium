package dev.phoenium.mixin;

import dev.phoenium.chunk.FullEntityFacts;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Visibility retirement precedes section lifetime retirement in the native callback. */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
abstract class RegionalEntityStorageRemovalMixin {
    @Shadow @Final private EntityAccess entity;
    @Shadow @Final private PersistentEntitySectionManager<?> this$0;

    @Inject(method = "onRemove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", at = @At("RETURN"))
    private void phoenium$unstored(Entity.RemovalReason reason, CallbackInfo callback) {
        if (entity instanceof Entity value) FullEntityFacts.unstored(value, this$0);
    }
}
