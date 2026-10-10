package dev.phoenium.engine.mixin;

import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-E1 (engine mode only): an entity added from a tile (spawned, dropped, shot) joins the level after the tile's colour,
 * on the server thread, in tile order; it is skipped if it was removed meanwhile. As in vanilla, a new entity is not
 * ticked in the pass that created it.
 */
@Mixin(PersistentEntitySectionManager.class)
abstract class EngineEntityManagerMixin<T extends EntityAccess> {
    @Shadow public abstract boolean addNewEntity(T entity);

    @Inject(method = "addNewEntity", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferAdd(T entity, CallbackInfoReturnable<Boolean> cir) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        context.defer(() -> {
            if (!entity.isRemoved()) this.addNewEntity(entity);
        });
        cir.setReturnValue(true);
    }
}
