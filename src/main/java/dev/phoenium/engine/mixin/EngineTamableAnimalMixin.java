package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.world.entity.TamableAnimal;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S9-E1 (engine mode only): a pet's teleport to its owner (FollowOwnerGoal, the pet panic goal) can land anywhere the
 * owner is. From a tile it runs after the tile's colour, on the server thread: the pet keeps ticking in its tile and
 * never acts outside its claim. The deferral stays within the tick (the owner, a player, ticks after every colour).
 */
@Mixin(TamableAnimal.class)
abstract class EngineTamableAnimalMixin {
    @WrapMethod(method = "tryToTeleportToOwner")
    private void phoenium$deferTeleport(Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call();
            return;
        }
        TamableAnimal pet = (TamableAnimal) (Object) this;
        context.defer(() -> {
            if (!pet.isRemoved()) pet.tryToTeleportToOwner();
        });
    }
}
