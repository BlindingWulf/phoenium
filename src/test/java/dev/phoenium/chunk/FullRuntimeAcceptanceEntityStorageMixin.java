package dev.phoenium.acceptance;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;

/** Observe exact native membership outcomes, including pre-start saved inputs. */
@Mixin(PersistentEntitySectionManager.class)
abstract class FullRuntimeAcceptanceEntityStorageMixin<T extends EntityAccess> {
    @WrapMethod(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z")
    private boolean inserted(T value, boolean loaded, Operation<Boolean> original) {
        boolean result = original.call(value, loaded);
        if (value instanceof Entity entity && entity.entityTags().stream().anyMatch(tag -> tag.startsWith("phoenium_full_")))
            System.out.println("PHOENIUM_FULL_ENTITY_INSERT entity=" + entity.getUUID() + " runtimeId=" + entity.getId()
                + " chunk=" + entity.chunkPosition() + " type=" + entity.getType() + " tags=" + entity.entityTags()
                + " loaded=" + loaded + " removed=" + entity.isRemoved() + " result=" + result);
        return result;
    }
}
