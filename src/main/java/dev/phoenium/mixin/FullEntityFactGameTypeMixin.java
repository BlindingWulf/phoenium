package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameType.class)
abstract class FullEntityFactGameTypeMixin {
    @WrapMethod(method = "updatePlayerAbilities")
    private void phoenium$abilities(Abilities abilities, Operation<Void> original) {
        Player player = ((FullPlayerAbilitiesAccess) abilities).phoenium$abilitiesOwner();
        try (var turn = player == null ? null : FullEntityFacts.writer(player)) {
            try { original.call(abilities); } finally { if (player != null) FullEntityFacts.publish(player); }
        }
    }
}
